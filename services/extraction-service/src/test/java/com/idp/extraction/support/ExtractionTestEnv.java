package com.idp.extraction.support;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import com.idp.events.JdbcOutboxPublisher;
import com.idp.events.OutboxRepository;
import com.idp.extraction.audit.AiExecutionRegistry;
import com.idp.extraction.core.ExtractionEvaluator;
import com.idp.extraction.core.ExtractionProcessor;
import com.idp.extraction.llm.LlmOrchestrator;
import com.idp.extraction.llm.ResilientLlmGateway;
import com.idp.extraction.reliability.Calibrator;
import com.idp.extraction.reliability.Grounding;
import com.idp.extraction.reliability.IdentityCalibrator;
import com.idp.extraction.reliability.ReliabilityEngine;
import com.idp.extraction.security.PromptInjectionDetector;
import com.idp.extraction.store.DocumentStoragePort;
import com.idp.extraction.store.ExtractionRepository;
import com.idp.extraction.store.JdbcExtractionRepository;
import com.idp.extraction.store.PageContent;
import com.idp.extraction.store.RenderedDocument;
import com.idp.extraction.typology.TypologyRegistry;
import com.idp.extraction.validation.ValidatorRegistry;
import com.idp.kms.InMemoryKeyService;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantConnection;
import com.idp.tenant.context.TenantContextHolder;
import com.idp.tenant.context.TenantDataSourceRouter;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Entorno de prueba de extremo a extremo sin Docker: silo por tenant sobre H2 (DataSource enrutado real de
 * tenant-context), outbox e idempotencia reales de libs/events, KMS en memoria y LlmProvider falso.
 */
public final class ExtractionTestEnv {

    public static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-05T15:00:00Z"), ZoneOffset.UTC);
    public static final String STANDARD_TEXT = "OFICIO 123 de 2026. Radicado 11001-31-03-005-2024-00123-00. Ciudad Bogota D.C. "
        + "Fecha 2026-09-30. Embargo coactivo contra los demandados. Valor total $15.000.000 (quince millones de pesos). "
        + "Cuenta de ahorros 123456789. Demandado Maria Perez C.C. 1.234.567 $10.000.000 Cuenta de ahorros 123456789. "
        + "Demandado Comercial SAS NIT 900.123.456-8 $5.000.000 cuenta corriente 987654321.";

    /** Storage en memoria; registra el tenant del contexto en cada lectura (AC-07). */
    public static final class FakeStorage implements DocumentStoragePort {
        private final Map<String, RenderedDocument> docs = new ConcurrentHashMap<>();
        public final List<String> contextTenantSeen = new ArrayList<>();

        public void put(UUID tenant, UUID doc, RenderedDocument rendered) {
            docs.put(tenant + "/" + doc, rendered);
        }

        public void putSinglePage(UUID tenant, UUID doc, String nativeText) {
            put(tenant, doc, new RenderedDocument(List.of(new PageContent(1, new byte[] {1, 2, 3}, nativeText))));
        }

        @Override
        public RenderedDocument load(TenantId tenant, UUID documentId) {
            contextTenantSeen.add(TenantContextHolder.getTenantId());
            RenderedDocument d = docs.get(tenant.value() + "/" + documentId);
            if (d == null) {
                throw new DocumentNotRenderedException("Documento sin paginas renderizadas");
            }
            return d;
        }
    }

    public final ObjectMapper mapper = new ObjectMapper();
    public final EventSerde serde = new EventSerde(mapper);
    public final MeterRegistry meters = new SimpleMeterRegistry();
    public final InMemoryKeyService keys = new InMemoryKeyService();
    public final FakeStorage storage = new FakeStorage();
    public final ValidatorRegistry validators = ValidatorRegistry.defaults();
    public final TypologyRegistry typologies = TypologyRegistry.loadDefault(validators);
    public final TenantDataSourceRouter router;
    public final JdbcTemplate jdbc;
    public final TransactionTemplate tx;
    public final ExtractionRepository repository;
    public final AiExecutionRegistry aiRegistry;
    public final IdempotentEventConsumer consumer;
    public final ExtractionProcessor processor;
    public final ResilientLlmGateway gateway;
    public final FakeLlm primary;
    public final FakeLlm secondary;

    private ExtractionTestEnv(FakeLlm primary, FakeLlm secondary, Calibrator calibrator) {
        this.primary = primary;
        this.secondary = secondary;
        this.router = new TenantDataSourceRouter(
            tenantId -> new TenantConnection("jdbc:h2:mem:extr_" + tenantId.replace('-', '_')
                + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", ""),
            10,
            c -> {
                DriverManagerDataSource ds = new DriverManagerDataSource(c.jdbcUrl(), c.username(), c.password());
                initSchema(ds);
                return ds;
            });
        this.jdbc = new JdbcTemplate(router);
        this.tx = new TransactionTemplate(new DataSourceTransactionManager(router));
        this.repository = new JdbcExtractionRepository(jdbc, meters, false);
        EventSchemaValidator schemaValidator = new EventSchemaValidator(serde);
        this.consumer = new IdempotentEventConsumer(jdbc, tx, schemaValidator, serde);
        JdbcOutboxPublisher outbox = new JdbcOutboxPublisher(new OutboxRepository(jdbc), schemaValidator, serde,
            com.idp.events.EventTopology.defaults(), "extraction-service");
        this.aiRegistry = new AiExecutionRegistry(keys, repository, mapper, "ai-registry");
        this.gateway = new ResilientLlmGateway(primary, secondary, Duration.ofSeconds(5), 4, Duration.ofMillis(50),
            CircuitBreakerConfig.custom().failureRateThreshold(50).slidingWindowSize(2).minimumNumberOfCalls(2)
                .waitDurationInOpenState(Duration.ofMinutes(5)).build());
        ReliabilityEngine reliability = new ReliabilityEngine(calibrator);
        ExtractionEvaluator evaluator = new ExtractionEvaluator(validators, reliability, new Grounding());
        this.processor = new ExtractionProcessor(storage, new LlmOrchestrator(gateway, mapper), typologies, evaluator,
            calibrator.id(), new PromptInjectionDetector(), repository, outbox, aiRegistry,
            new ExtractionProcessor.Settings(0.7, new BigDecimal("0.5"), new BigDecimal("1.5")), CLOCK, mapper, meters);
    }

    public static ExtractionTestEnv create(FakeLlm primary, FakeLlm secondary) {
        return new ExtractionTestEnv(primary, secondary, new IdentityCalibrator());
    }

    private static void initSchema(DataSource ds) {
        try (var conn = ds.getConnection()) {
            ScriptUtils.executeSqlScript(conn, new ClassPathResource("schema-h2.sql"));
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Comando extraccion.solicitada serializado con un eventId nuevo. */
    public String command(UUID tenant, UUID document, String typology) {
        return command(UUID.randomUUID(), tenant, document, typology);
    }

    public String command(UUID eventId, UUID tenant, UUID document, String typology) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("documentId", document.toString());
        payload.put("typology", typology);
        return serde.toJson(new EventEnvelope(eventId, "extraccion.solicitada", 1, CLOCK.instant(), tenant,
            UUID.randomUUID(), payload));
    }

    public IdempotentEventConsumer.Result run(String json) {
        return consumer.consume(json, processor::process);
    }

    public <T> T inTenant(UUID tenant, Supplier<T> work) {
        String previous = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenant.toString());
        try {
            return work.get();
        } finally {
            if (previous == null) {
                TenantContextHolder.clear();
            } else {
                TenantContextHolder.setTenantId(previous);
            }
        }
    }

    /** Tipos de evento del outbox del tenant, en orden de insercion. */
    public List<String> outboxTypes(UUID tenant) {
        return inTenant(tenant, () -> jdbc.queryForList("select event_type from outbox order by created_at, event_type",
            String.class));
    }

    public List<String> outboxPayloads(UUID tenant) {
        return inTenant(tenant, () -> jdbc.queryForList("select payload from outbox", String.class));
    }

    public String outboxPayload(UUID tenant, String type) {
        return inTenant(tenant, () -> jdbc.queryForObject("select payload from outbox where event_type = ?",
            String.class, type));
    }
}
