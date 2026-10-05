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
import com.idp.kms.OpenBaoTransitKeyService;
import com.idp.llm.LlmProvider;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.storage.ObjectStore;
import com.idp.storage.S3Clients;
import com.idp.storage.S3ObjectStore;
import com.idp.tenant.context.OpenBaoTenantCredentialProvider;
import com.idp.tenant.context.TenantCredentialProvider;
import com.idp.tenant.context.TenantDataSourceRouter;
import io.micrometer.core.instrument.MeterRegistry;
import java.net.URI;
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
 * storage S3, KMS OpenBao, LLM (Spring AI) con bulkhead por tenant y el procesador de extraccion.
 * Se desactiva con {@code extraction.runtime.enabled=false} (tests de arranque sin infraestructura).
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "extraction.runtime.enabled", havingValue = "true", matchIfMissing = true)
public class ExtractionRuntimeConfig {

    @Bean
    TenantCredentialProvider tenantCredentialProvider(RestClient.Builder builder, ExtractionProperties props) {
        ExtractionProperties.Openbao o = props.openbao();
        return new OpenBaoTenantCredentialProvider(builder, o.address(), o::token, o.credsPath(), o.jdbcUrl());
    }

    @Bean
    @Primary
    DataSource tenantDataSource(TenantCredentialProvider provider, ExtractionProperties props) {
        return new TenantDataSourceRouter(provider, props.db().maxPools(), props.db().poolSize());
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
    OutboxPublisher outboxPublisher(JdbcTemplate jdbc, EventSchemaValidator validator, EventSerde serde) {
        return new JdbcOutboxPublisher(new OutboxRepository(jdbc), validator, serde);
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

    @Bean
    ObjectStore objectStore(ExtractionProperties props) {
        ExtractionProperties.Storage s = props.storage();
        URI endpoint = s.endpoint().isBlank() ? null : URI.create(s.endpoint());
        String access = s.accessKey().isBlank() ? null : s.accessKey();
        return new S3ObjectStore(S3Clients.create(endpoint, s.region(), access, s.secretKey(), s.pathStyle()),
            s.bucket());
    }

    @Bean
    DocumentStoragePort documentStorage(ObjectStore store, KeyService keys, ExtractionProperties props,
                                        ObjectMapper idpObjectMapper) {
        ExtractionProperties.Storage s = props.storage();
        return new EncryptedDocumentStorageAdapter(
            new EncryptedArtifactStore(store, new EnvelopeCrypto(keys), s.kekId()), idpObjectMapper, s.maxPages());
    }

    @Bean
    KeyService keyService(RestClient.Builder builder, ExtractionProperties props) {
        ExtractionProperties.Openbao o = props.openbao();
        return new OpenBaoTransitKeyService(builder, o.address(), o::token, o.transitMount());
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
