package com.idp.extraction.core;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import com.idp.extraction.audit.AiExecutionRegistry;
import com.idp.extraction.core.ExtractionEvaluator.FieldEval;
import com.idp.extraction.llm.ExtractionRun;
import com.idp.extraction.llm.LlmOrchestrator;
import com.idp.extraction.llm.LlmOrchestrator.Classification;
import com.idp.extraction.llm.PromptTemplates;
import com.idp.extraction.security.PromptInjectionDetector;
import com.idp.extraction.store.DocumentStoragePort;
import com.idp.extraction.store.ExtractionRepository;
import com.idp.extraction.store.ExtractionRepository.ExtractionRecord;
import com.idp.extraction.store.ExtractionRepository.FieldRecord;
import com.idp.extraction.store.ExtractionRepository.Status;
import com.idp.extraction.store.RenderedDocument;
import com.idp.extraction.typology.TypologyDef;
import com.idp.extraction.typology.TypologyRegistry;
import com.idp.tenant.TenantId;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flujo completo de extraccion de un oficio. Se ejecuta dentro de la transaccion del consumidor
 * idempotente: persistencia, registro firmado de IA y eventos del outbox son atomicos.
 * Orden: idempotencia, lectura del silo, filtro de prompt injection, clasificacion few-shot,
 * extraccion, validadores + score calibrado, cascada focalizada y ruteo en tres tramos.
 */
public final class ExtractionProcessor {

    private static final Logger LOG = LoggerFactory.getLogger(ExtractionProcessor.class);
    private static final int MAX_ERROR = 500;

    /** Parametros de negocio del procesador. */
    public record Settings(double classificationMinConfidence, BigDecimal priceInputPer1k, BigDecimal priceOutputPer1k) {
    }

    private final DocumentStoragePort storage;
    private final LlmOrchestrator llm;
    private final TypologyRegistry typologies;
    private final ExtractionEvaluator evaluator;
    private final PromptInjectionDetector injection;
    private final ExtractionRepository repository;
    private final OutboxPublisher outbox;
    private final AiExecutionRegistry aiRegistry;
    private final String calibratorId;
    private final Settings settings;
    private final Clock clock;
    private final ObjectMapper mapper;
    private final MeterRegistry meters;

    public ExtractionProcessor(DocumentStoragePort storage, LlmOrchestrator llm, TypologyRegistry typologies,
                               ExtractionEvaluator evaluator, String calibratorId, PromptInjectionDetector injection,
                               ExtractionRepository repository, OutboxPublisher outbox, AiExecutionRegistry aiRegistry,
                               Settings settings, Clock clock, ObjectMapper mapper, MeterRegistry meters) {
        this.storage = storage;
        this.llm = llm;
        this.typologies = typologies;
        this.evaluator = evaluator;
        this.calibratorId = calibratorId;
        this.injection = injection;
        this.repository = repository;
        this.outbox = outbox;
        this.aiRegistry = aiRegistry;
        this.settings = settings;
        this.clock = clock;
        this.mapper = mapper;
        this.meters = meters;
    }

    /** Procesa el comando {@code extraccion.solicitada}. El tenant sale del sobre validado (nunca del payload). */
    public void process(EventEnvelope cmd) {
        UUID tenantId = cmd.tenantId();
        TenantId tenant = new TenantId(tenantId.toString());
        UUID documentId = UUID.fromString(cmd.payload().path("documentId").asText());
        String hint = cmd.payload().path("typology").asText();

        if (repository.existsFinished(documentId)) {
            LOG.info("Extraccion ya finalizada para el documento; comando ignorado");
            return;
        }
        Context ctx = new Context(cmd, tenant, documentId, UUID.randomUUID(), new ExtractionRun(tenant),
            System.nanoTime());

        if (typologies.active(hint).isEmpty()) {
            terminalReview(ctx, Status.UNSUPPORTED, null, "Tipologia no soportada");
            return;
        }
        RenderedDocument doc = storage.load(tenant, documentId);
        String text = doc.nativeText();

        Optional<String> rule = injection.scan(text);
        if (rule.isPresent()) {
            abortInjection(ctx, rule.get());
            return;
        }

        Classification c;
        try {
            c = llm.classify(ctx.run, typologies.activeDefinitions(), doc.pages(), text);
        } catch (LlmOrchestrator.LlmOutputException e) {
            terminalReview(ctx, Status.CLASSIFICATION_REVIEW, null, "Salida de clasificacion invalida");
            return;
        }
        if ("NO_OFICIO".equals(c.code())) {
            terminalReview(ctx, Status.NO_OFICIO, null, "Documento no es un oficio");
            return;
        }
        Optional<TypologyDef> typology = typologies.active(c.code());
        if (typology.isEmpty()) {
            terminalReview(ctx, Status.UNSUPPORTED, null, "Tipologia no soportada");
            return;
        }
        if (c.confidence() < settings.classificationMinConfidence() || !c.code().equals(hint)) {
            terminalReview(ctx, Status.CLASSIFICATION_REVIEW, typology.get(), "Clasificacion incierta o discrepante");
            return;
        }
        extractAndRoute(ctx, typology.get(), doc, text);
    }

    private void extractAndRoute(Context ctx, TypologyDef typology, RenderedDocument doc, String text) {
        LocalDate today = LocalDate.now(clock);
        ExtractionData data;
        try {
            data = llm.extract(ctx.run, typology, doc.pages(), text);
        } catch (LlmOrchestrator.LlmOutputException e) {
            terminalReview(ctx, Status.CLASSIFICATION_REVIEW, typology, "Salida de extraccion invalida");
            return;
        }
        Map<FieldKey, FieldEval> evals = evaluator.evaluate(typology, data, text, today);

        List<FieldKey> doubtful = evals.values().stream()
            .filter(e -> !e.skipped() && e.decision() == com.idp.extraction.reliability.ReliabilityEngine.Decision.CASCADE)
            .map(FieldEval::key).toList();
        if (!doubtful.isEmpty()) {
            try {
                ExtractionData second = llm.secondPass(ctx.run, typology, doubtful, doc.pages(), text);
                boolean merged = false;
                for (FieldKey k : doubtful) {
                    ExtractedValue v = second.get(k);
                    if (v != null && v.present()) {
                        data.put(k, v);
                        merged = true;
                    }
                }
                if (merged) {
                    evals = evaluator.evaluate(typology, data, text, today);
                }
            } catch (LlmOrchestrator.LlmOutputException e) {
                LOG.warn("Segunda pasada con salida invalida; los campos dudosos pasan a revision");
            }
        }

        boolean review = evals.values().stream().anyMatch(FieldEval::requiresReview);
        List<FieldRecord> fields = toRecords(evals);
        BigDecimal overall = evals.values().stream().filter(e -> !e.skipped())
            .map(e -> BigDecimal.valueOf(e.score().score())).min(BigDecimal::compareTo)
            .orElse(BigDecimal.ZERO).setScale(4, RoundingMode.HALF_UP);
        persistAndPublish(ctx, review ? Status.REQUIRES_REVIEW : Status.COMPLETED, typology, overall, fields,
            review ? "Campos por revisar: " + evals.values().stream().filter(FieldEval::requiresReview).count() : null);
    }

    private List<FieldRecord> toRecords(Map<FieldKey, FieldEval> evals) {
        List<FieldRecord> out = new ArrayList<>();
        for (FieldEval e : evals.values()) {
            ExtractedValue v = e.value();
            Evidence ev = v == null ? null : v.evidence();
            String bbox = null;
            if (ev != null && ev.bbox() != null) {
                try {
                    bbox = mapper.writeValueAsString(ev.bbox());
                } catch (JsonProcessingException ex) {
                    throw new IllegalStateException("bbox no serializable", ex);
                }
            }
            String error = e.errorCode();
            if (error != null && error.length() > MAX_ERROR) {
                error = error.substring(0, MAX_ERROR);
            }
            out.add(new FieldRecord(UUID.randomUUID(), e.key().table(), e.key().isCell() ? e.key().row() : null,
                e.key().name(), v == null ? null : v.value(),
                e.score() == null ? null : BigDecimal.valueOf(e.score().score()).setScale(4, RoundingMode.HALF_UP),
                ev == null ? null : ev.page(), ev == null ? null : ev.quote(), bbox, e.requiresReview(), error));
        }
        return out;
    }

    private void abortInjection(Context ctx, String ruleId) {
        LOG.warn("Prompt injection detectado (regla {}); extraccion abortada", ruleId);
        repository.save(extractionRecord(ctx, Status.ABORTED_INJECTION, null, BigDecimal.ZERO, null,
            "Prompt injection: " + ruleId), List.of());
        ObjectNode p = mapper.createObjectNode();
        p.put("documentId", ctx.documentId.toString());
        p.put("source", "EXTRACTION");
        publish(ctx, "seguridad.prompt_injection_detectado", "injection", p);
        meters.counter("idp.extraction.outcome", "outcome", Status.ABORTED_INJECTION).increment();
    }

    private void terminalReview(Context ctx, String status, TypologyDef typology, String detail) {
        persistAndPublish(ctx, status, typology, BigDecimal.ZERO, List.of(), detail);
    }

    private void persistAndPublish(Context ctx, String status, TypologyDef typology, BigDecimal overall,
                                   List<FieldRecord> fields, String detail) {
        UUID taskId = Status.COMPLETED.equals(status) ? null : UUID.randomUUID();
        ExtractionRecord record = extractionRecord(ctx, status, typology, overall, taskId, detail);
        repository.save(record, fields);
        if (ctx.run.llmCalls() > 0) {
            ExtractionRepository.AiRecord ai = aiRegistry.record(ctx.cmd.tenantId(), ctx.documentId, ctx.extractionId,
                ctx.run, new AiExecutionRegistry.Config(PromptTemplates.VERSION,
                    typology == null ? "UNKNOWN" : typology.code(), typology == null ? null : typology.version(),
                    calibratorId, settings.classificationMinConfidence(), "tau_auto/tau_revisar-cascada"),
                clock.instant());
            publishAiExecution(ctx, ai);
        }
        ObjectNode p = mapper.createObjectNode();
        p.put("documentId", ctx.documentId.toString());
        if (Status.COMPLETED.equals(status)) {
            if (typology != null) {
                p.put("typology", typology.code());
            }
            p.put("modelPromptKey", modelPromptKey(ctx.run));
            p.put("latencyMs", Math.max(0L, (System.nanoTime() - ctx.startNanos) / 1_000_000L));
            p.put("costMicros", costMicros(ctx.run));
            publish(ctx, "extraccion.completada", "result", p);
        } else {
            p.put("taskId", taskId.toString());
            if (typology != null) {
                p.put("typology", typology.code());
            }
            publish(ctx, "extraccion.requiere_revision", "result", p);
        }
        publishConsumption(ctx, status);
        meters.counter("idp.extraction.outcome", "outcome", status).increment();
    }

    /** Par modelo+prompt como en el registro firmado (modelos observados y version de prompt), solo charset del esquema. */
    private static String modelPromptKey(ExtractionRun run) {
        String models = run.modelVersions().isEmpty() ? "unknown" : String.join("/", run.modelVersions());
        String key = (models + ":" + PromptTemplates.VERSION).replaceAll("[^A-Za-z0-9._:/-]", "_");
        return key.length() > 120 ? key.substring(key.length() - 120) : key;
    }

    private long costMicros(ExtractionRun run) {
        return costOf(run).movePointRight(6).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }

    private BigDecimal costOf(ExtractionRun run) {
        return settings.priceInputPer1k().multiply(BigDecimal.valueOf(run.tokensIn()))
            .add(settings.priceOutputPer1k().multiply(BigDecimal.valueOf(run.tokensOut())))
            .divide(BigDecimal.valueOf(1000), 6, RoundingMode.HALF_UP);
    }

    /** SEC-049: evento sin PII con versiones, huella de configuracion y referencia a la firma del registro. */
    private void publishAiExecution(Context ctx, ExtractionRepository.AiRecord ai) {
        ObjectNode p = mapper.createObjectNode();
        p.put("documentId", ctx.documentId.toString());
        String models = ctx.run.modelVersions().stream().sorted().collect(java.util.stream.Collectors.joining(","));
        p.put("modelVersion", models.isEmpty() ? "unknown" : models);
        p.put("promptVersion", PromptTemplates.VERSION);
        p.put("configHash", sha256Hex(ai.payload()));
        p.put("signatureRef", ai.keyId() + ":" + ai.id());
        publish(ctx, "ia.ejecucion_registrada", "ia", p);
    }

    private static String sha256Hex(String data) {
        try {
            return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void publishConsumption(Context ctx, String status) {
        if (ctx.run.tokensIn() > 0) {
            consumption(ctx, "LLM_TOKENS_PROMPT", ctx.run.tokensIn());
        }
        if (ctx.run.tokensOut() > 0) {
            consumption(ctx, "LLM_TOKENS_COMPLETION", ctx.run.tokensOut());
        }
        if (Status.COMPLETED.equals(status) || Status.REQUIRES_REVIEW.equals(status)) {
            consumption(ctx, "DOCUMENTS_EXTRACTED", 1);
        }
        meters.counter("idp.extraction.llm.tokens", "direction", "input").increment(ctx.run.tokensIn());
        meters.counter("idp.extraction.llm.tokens", "direction", "output").increment(ctx.run.tokensOut());
    }

    private void consumption(Context ctx, String metric, int value) {
        ObjectNode p = mapper.createObjectNode();
        p.put("metricName", metric);
        p.put("value", value);
        publish(ctx, "consumo.registrado", metric, p);
    }

    private void publish(Context ctx, String type, String suffix, ObjectNode payload) {
        UUID eventId = UUID.nameUUIDFromBytes((ctx.cmd.eventId() + "|" + type + "|" + suffix)
            .getBytes(StandardCharsets.UTF_8));
        outbox.publish(ctx.documentId.toString(), new EventEnvelope(eventId, type, 1, clock.instant(),
            ctx.cmd.tenantId(), ctx.cmd.correlationId(), payload));
    }

    private ExtractionRecord extractionRecord(Context ctx, String status, TypologyDef typology, BigDecimal overall,
                                              UUID taskId, String detail) {
        BigDecimal cost = costOf(ctx.run);
        String models = String.join(",", ctx.run.modelVersions());
        Instant now = clock.instant();
        return new ExtractionRecord(ctx.extractionId, ctx.documentId, ctx.cmd.tenantId(), status,
            typology == null ? null : typology.code(), typology == null ? null : typology.version(),
            models.isEmpty() ? null : models, PromptTemplates.VERSION, overall, ctx.run.tokensIn(),
            ctx.run.tokensOut(), cost, taskId, detail, now);
    }

    private record Context(EventEnvelope cmd, TenantId tenant, UUID documentId, UUID extractionId, ExtractionRun run,
                           long startNanos) {
    }
}
