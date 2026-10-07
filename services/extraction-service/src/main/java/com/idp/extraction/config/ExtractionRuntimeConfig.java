package com.idp.extraction.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.events.EventErrorHandlers;
import com.idp.events.EventSchemaValidator;
import com.idp.events.EventSerde;
import com.idp.events.IdempotentEventConsumer;
import com.idp.events.JdbcOutboxPublisher;
import com.idp.events.OutboxPublisher;
import com.idp.events.OutboxRepository;
import com.idp.extraction.audit.AiExecutionRegistry;
import com.idp.extraction.core.ExtractionEvaluator;
import com.idp.extraction.core.ExtractionProcessor;
import com.idp.extraction.llm.LlmOrchestrator;
import com.idp.extraction.llm.ResilientLlmGateway;
import com.idp.extraction.llm.SpringAiLlmProvider;
import com.idp.extraction.reliability.Calibrator;
import com.idp.extraction.reliability.Grounding;
import com.idp.extraction.reliability.ReliabilityEngine;
import com.idp.extraction.security.PromptInjectionDetector;
import com.idp.extraction.store.DocumentStoragePort;
import com.idp.extraction.store.ExtractionRepository;
import com.idp.extraction.store.JdbcExtractionRepository;
import com.idp.extraction.store.EncryptedDocumentStorageAdapter;
import com.idp.extraction.typology.TypologyRegistry;
import com.idp.extraction.validation.ValidatorRegistry;
import com.idp.kms.EnvelopeCrypto;
import com.idp.kms.KeyService;
import com.idp.llm.LlmProvider;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.storage.ObjectStore;
import com.idp.tenant.context.OpenBaoTenantCredentialProvider;
import com.idp.tenant.context.TenantCredentialProvider;
import com.idp.tenant.context.TenantDataSourceRouter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.ZoneId;
import javax.sql.DataSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/**
 * Cableado de infraestructura: silo por tenant (DataSource enrutado), outbox/consumidor idempotente,
 * LLM (Spring AI) con bulkhead por tenant y el procesador de extraccion.
 * Se desactiva con {@code extraction.runtime.enabled=false} (tests de arranque sin infraestructura).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "extraction.runtime.enabled", havingValue = "true", matchIfMissing = true)
public class ExtractionRuntimeConfig {

    @Bean
    TenantCredentialProvider tenantCredentialProvider(RestClient.Builder builder, ExtractionProperties props,
                                                      @org.springframework.beans.factory.annotation.Value("${idp.security.dev-mode:false}") boolean devMode,
                                                      ObjectProvider<org.springframework.boot.ssl.SslBundles> bundles) {
        ExtractionProperties.Openbao o = props.openbao();
        javax.net.ssl.SSLContext ssl = null;
        if (o.sslBundle() != null && !o.sslBundle().isBlank()) {
            ssl = bundles.getObject().getBundle(o.sslBundle()).createSslContext();
        }
        return new OpenBaoTenantCredentialProvider(builder, o.address(), o::token, o.credsPath(), o.jdbcUrl(), devMode, ssl);
    }

    @Bean
    @Primary
    TenantDataSourceRouter tenantDataSource(TenantCredentialProvider provider, ExtractionProperties props) {
        return new TenantDataSourceRouter(provider, props.db().maxPools(), props.db().poolSize());
    }

    /** tenant.baja_iniciada / rotacion de credenciales desalojan el pool con drenado (H7). */
    @Bean
    com.idp.security.TenantPoolEvictionKafkaListener tenantPoolEvictionListener(TenantDataSourceRouter router,
                                                                              EventSerde serde,
            com.idp.events.EventOriginGuard guard) {
        return new com.idp.security.TenantPoolEvictionKafkaListener(router, serde, guard);
    }

    @Bean
    TransactionTemplate tenantTransactionTemplate(DataSource tenantDataSource) {
        return new TransactionTemplate(new DataSourceTransactionManager(tenantDataSource));
    }

    @Bean
    EventSerde eventSerde(ObjectMapper idpObjectMapper) {
        return new EventSerde(idpObjectMapper);
    }

    @Bean
    EventSchemaValidator eventSchemaValidator(EventSerde serde) {
        return new EventSchemaValidator(serde);
    }

    @Bean
    com.idp.events.EventOriginGuard eventOriginGuard(
            org.springframework.beans.factory.ObjectProvider<io.micrometer.core.instrument.MeterRegistry> registry) {
        return new com.idp.events.EventOriginGuard(com.idp.events.EventTopology.defaults(),
            registry.getIfAvailable());
    }

    @Bean
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, EventSchemaValidator validator, EventSerde serde) {
        return new JdbcOutboxPublisher(new OutboxRepository(jdbc), validator, serde,
            com.idp.events.EventTopology.defaults(), "extraction-service");
    }

    @Bean
    IdempotentEventConsumer idempotentEventConsumer(JdbcTemplate jdbc, TransactionTemplate tx,
                                                    EventSchemaValidator validator, EventSerde serde) {
        return new IdempotentEventConsumer(jdbc, tx, validator, serde);
    }

    @Bean
    CommonErrorHandler extractionErrorHandler(KafkaTemplate<?, ?> kafka) {
        return EventErrorHandlers.deadLetter(kafka);
    }

    /** Bucket por tenant desde silo_location; sin base de control, bucket compartido de desarrollo. */
    @Bean
    com.idp.tenant.context.TenantBucketResolver tenantBucketResolver(ExtractionProperties props, Clock clock) {
        ExtractionProperties.Control c = props.control();
        if (c.url().isBlank()) {
            return com.idp.tenant.context.TenantBucketResolver.fixed(props.storage().bucket());
        }
        com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(c.url());
        ds.setUsername(c.username());
        ds.setPassword(c.password());
        ds.setMaximumPoolSize(2);
        return new com.idp.tenant.context.TenantBucketResolver(new JdbcTemplate(ds), c.directoryTtl(), clock);
    }

    @Bean
    com.idp.tenant.context.TenantKeyResolver tenantKeyResolver(ExtractionProperties props, Clock clock) {
        ExtractionProperties.Control c = props.control();
        if (c.url().isBlank()) {
            return new com.idp.tenant.context.TenantKeyResolver(null, c.directoryTtl(), clock) {
                @Override
                public com.idp.tenant.context.TenantKeyResolver.TenantKeys resolve(String tenantId) {
                    return new com.idp.tenant.context.TenantKeyResolver.TenantKeys(props.storage().kekId(), props.registry().signingKeyId());
                }
            };
        }
        com.zaxxer.hikari.HikariDataSource ds = new com.zaxxer.hikari.HikariDataSource();
        ds.setJdbcUrl(c.url());
        ds.setUsername(c.username());
        ds.setPassword(c.password());
        ds.setMaximumPoolSize(2);
        return new com.idp.tenant.context.TenantKeyResolver(new JdbcTemplate(ds), c.directoryTtl(), clock);
    }

    @Bean
    DocumentStoragePort documentStorage(ObjectStore store, KeyService keys, ExtractionProperties props,
                                        ObjectMapper idpObjectMapper, com.idp.tenant.context.TenantKeyResolver keyResolver) {
        ExtractionProperties.Storage s = props.storage();
        return new EncryptedDocumentStorageAdapter(
            new EncryptedArtifactStore(store, new EnvelopeCrypto(keys), keyResolver), idpObjectMapper, s.maxPages());
    }

    @Bean
    ExtractionRepository extractionRepository(JdbcTemplate jdbc, MeterRegistry meters, ExtractionProperties props) {
        return new JdbcExtractionRepository(jdbc, meters, props.db().jsonbColumns());
    }

    /** Proveedor principal sobre el ChatModel del starter elegido; secundario = otro modelo del mismo vendor. */
    @Bean
    ResilientLlmGateway llmGateway(ObjectProvider<ChatModel> chatModel, ExtractionProperties props) {
        ChatModel model = chatModel.getObject();
        ExtractionProperties.Llm llm = props.llm();
        LlmProvider primary = new SpringAiLlmProvider(model, null);
        LlmProvider secondary = llm.fallbackModel().isBlank() ? null : new SpringAiLlmProvider(model, llm.fallbackModel());
        return new ResilientLlmGateway(primary, secondary, llm.timeout(), llm.bulkheadMaxConcurrent(),
            llm.bulkheadMaxWait(), ResilientLlmGateway.defaultBreakerConfig());
    }

    @Bean
    LlmOrchestrator llmOrchestrator(ResilientLlmGateway gateway, ObjectMapper idpObjectMapper) {
        return new LlmOrchestrator(gateway, idpObjectMapper);
    }

    @Bean
    ReliabilityEngine reliabilityEngine(Calibrator calibrator) {
        return new ReliabilityEngine(calibrator);
    }

    @Bean
    ExtractionEvaluator extractionEvaluator(ValidatorRegistry validators, ReliabilityEngine reliability) {
        return new ExtractionEvaluator(validators, reliability, new Grounding());
    }

    @Bean
    AiExecutionRegistry aiExecutionRegistry(KeyService keys, ExtractionRepository repository,
                                            ObjectMapper idpObjectMapper, ExtractionProperties props) {
        return new AiExecutionRegistry(keys, repository, idpObjectMapper, props.registry().signingKeyId());
    }

    @Bean
    Clock clock() {
        return Clock.system(ZoneId.of("America/Bogota"));
    }

    @Bean
    ExtractionProcessor extractionProcessor(DocumentStoragePort storage, LlmOrchestrator llm,
                                            TypologyRegistry typologies, ExtractionEvaluator evaluator,
                                            Calibrator calibrator, ExtractionRepository repository,
                                            OutboxPublisher outbox, AiExecutionRegistry aiRegistry,
                                            ExtractionProperties props, Clock clock, ObjectMapper idpObjectMapper,
                                            MeterRegistry meters) {
        ExtractionProcessor.Settings settings = new ExtractionProcessor.Settings(
            props.classification().minConfidence(), props.llm().priceInputPer1k(), props.llm().priceOutputPer1k());
        return new ExtractionProcessor(storage, llm, typologies, evaluator, calibrator.id(),
            new PromptInjectionDetector(), repository, outbox, aiRegistry, settings, clock, idpObjectMapper, meters);
    }
}
