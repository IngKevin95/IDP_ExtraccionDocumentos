package com.idp.extraction.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuracion externa del servicio (sin secretos en codigo: tokens y claves llegan por entorno). */
@ConfigurationProperties(prefix = "extraction")
public record ExtractionProperties(
    @DefaultValue Classification classification,
    @DefaultValue Llm llm,
    @DefaultValue Storage storage,
    @DefaultValue Registry registry,
    @DefaultValue Kafka kafka,
    @DefaultValue Calibration calibration,
    @DefaultValue Relay relay,
    @DefaultValue Db db,
    @DefaultValue Openbao openbao) {

    public record Classification(@DefaultValue("0.7") double minConfidence) {
    }

    /** {@code fallbackModel}: modelo del mismo vendor para failover y segunda pasada (vacio = sin secundario). */
    public record Llm(@DefaultValue("PT60S") Duration timeout, @DefaultValue("4") int bulkheadMaxConcurrent,
                      @DefaultValue("PT2S") Duration bulkheadMaxWait, @DefaultValue("") String fallbackModel,
                      @DefaultValue("0") BigDecimal priceInputPer1k, @DefaultValue("0") BigDecimal priceOutputPer1k) {
    }

    public record Storage(@DefaultValue("") String endpoint, @DefaultValue("us-east-1") String region,
                          @DefaultValue("idp-documents") String bucket, @DefaultValue("") String accessKey,
                          @DefaultValue("") String secretKey, @DefaultValue("true") boolean pathStyle,
                          @DefaultValue("documents/%s/pages/page_%d.png") String pageTemplate,
                          @DefaultValue("documents/%s/text/page_%d.txt") String textTemplate,
                          @DefaultValue("100") int maxPages) {
    }

    public record Registry(@DefaultValue("ai-registry") String signingKeyId) {
    }

    public record Kafka(@DefaultValue("dominio.documentos") String commandTopic) {
    }

    /** {@code resource}: JSON de la curva isotonica (file: o classpath:); vacio = calibracion identidad. */
    public record Calibration(@DefaultValue("") String resource) {
    }

    /** Relay del outbox hacia Kafka para los tenants listados. */
    public record Relay(@DefaultValue("false") boolean enabled, @DefaultValue List<String> tenants,
                        @DefaultValue("PT1S") Duration interval) {
    }

    public record Db(@DefaultValue("50") int maxPools, @DefaultValue("5") int poolSize,
                     @DefaultValue("true") boolean jsonbColumns) {
    }

    public record Openbao(@DefaultValue("http://openbao:8200") String address,
                          @DefaultValue("") String token,
                          @DefaultValue("database/creds/{tenant}") String credsPath,
                          @DefaultValue("jdbc:postgresql://postgres:5432/tenant_{tenant}") String jdbcUrl,
                          @DefaultValue("transit") String transitMount) {
    }
}
