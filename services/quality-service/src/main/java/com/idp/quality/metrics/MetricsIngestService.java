package com.idp.quality.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.events.EventEnvelope;
import com.idp.events.EventValidationException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Ingesta de eventos del pipeline hacia agregados de calidad. De cada evento solo se conservan contadores, tipologia,
 * nombre del campo corregido y tipo de correccion; jamas un valor extraido ni un enlace al documento (SEC-050).
 * Se invoca dentro de la transaccion de idempotencia (processed_event), asi un reintento no cuenta dos veces.
 */
@Service
public class MetricsIngestService {

    public static final String APROBADA = "extraccion.aprobada";
    public static final String REVISION = "revision.completada";
    public static final String COMPLETADA = "extraccion.completada";
    public static final Set<String> HANDLED = Set.of(APROBADA, REVISION, COMPLETADA);

    static final String UNKNOWN_TYPOLOGY = "NA";
    private static final Pattern TYPOLOGY = Pattern.compile("[A-Z]{2,4}");
    private static final Pattern FIELD = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final Set<String> CORRECTION_TYPES = Set.of("VALOR", "FORMATO", "OMISION", "SOBRANTE");

    private final MetricsRepository repo;
    private final BlindSampler sampler;

    public MetricsIngestService(MetricsRepository repo, BlindSampler sampler) {
        this.repo = repo;
        this.sampler = sampler;
    }

    public void handle(EventEnvelope e) {
        switch (e.eventType()) {
            case APROBADA -> aprobada(e);
            case REVISION -> revision(e);
            case COMPLETADA -> completada(e);
            default -> throw new EventValidationException("Evento no soportado por quality-service");
        }
    }

    /** AC-02: un STP incrementa el total y stp_count; toda aprobacion terminal incrementa el denominador. */
    private void aprobada(EventEnvelope e) {
        String tip = typology(e);
        LocalDate day = day(e);
        boolean stp = "AUTO_STP".equals(e.payload().path("approvedBy").asText());
        repo.addDaily(e.tenantId(), day, tip, 1, stp ? 1 : 0, 0, 0, 0);
        if (stp && sampler.shouldSample(e.tenantId(), documentId(e), tip)) {
            repo.selectBlind(e.tenantId(), documentId(e), tip);
        }
    }

    /**
     * AC-01 y AC-03. Revision normal: hitl_count y errores por campo (un RECHAZADO cuenta ademas como documento
     * terminal, porque no habra extraccion.aprobada). Revision ciega: solo muestras y error silente, para no
     * contar dos veces un oficio ya contado como STP.
     */
    private void revision(EventEnvelope e) {
        String tip = typology(e);
        LocalDate day = day(e);
        JsonNode corrected = e.payload().path("correctedFields");
        boolean hasCorrections = corrected.isArray() && !corrected.isEmpty()
            || e.payload().path("criticalCorrection").asBoolean(false);
        boolean selected = repo.consumeBlind(e.tenantId(), documentId(e));
        boolean blind = selected || e.payload().path("blindSample").asBoolean(false);
        String origin = blind ? "BLIND" : "HITL";
        if (blind) {
            repo.addDaily(e.tenantId(), day, tip, 0, 0, 0, hasCorrections ? 1 : 0, 1);
        } else {
            boolean rejected = "RECHAZADO".equals(e.payload().path("action").asText());
            repo.addDaily(e.tenantId(), day, tip, rejected ? 1 : 0, 0, 1, 0, 0);
        }
        if (corrected.isArray()) {
            for (JsonNode c : corrected) {
                String field = c.path("field").asText("");
                String type = c.path("correctionType").asText("");
                if (!FIELD.matcher(field).matches() || !CORRECTION_TYPES.contains(type)) {
                    throw new EventValidationException("Campo corregido fuera de contrato");
                }
                repo.addFieldError(e.tenantId(), day, tip, field, type, origin);
            }
        }
    }

    private void completada(EventEnvelope e) {
        JsonNode p = e.payload();
        boolean hasLatency = p.path("latencyMs").isNumber();
        boolean hasCost = p.path("costMicros").isNumber();
        if (!hasLatency && !hasCost) {
            return;
        }
        String key = p.path("modelPromptKey").isTextual() ? p.get("modelPromptKey").asText() : null;
        repo.addExtractionSample(e.tenantId(), day(e), typology(e), key,
            hasLatency ? p.get("latencyMs").asInt() : null, hasCost ? p.get("costMicros").asLong() : null);
    }

    private static UUID documentId(EventEnvelope e) {
        try {
            return UUID.fromString(e.payload().path("documentId").asText());
        } catch (IllegalArgumentException ex) {
            throw new EventValidationException("documentId invalido", ex);
        }
    }

    private static String typology(EventEnvelope e) {
        String t = e.payload().path("typology").asText(UNKNOWN_TYPOLOGY);
        return TYPOLOGY.matcher(t).matches() ? t : UNKNOWN_TYPOLOGY;
    }

    private static LocalDate day(EventEnvelope e) {
        return e.occurredAt().atZone(ZoneOffset.UTC).toLocalDate();
    }
}
