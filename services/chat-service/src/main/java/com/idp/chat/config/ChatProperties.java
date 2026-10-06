package com.idp.chat.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Parametros del chat-service (idp.chat.*). */
@ConfigurationProperties("idp.chat")
public record ChatProperties(
        @DefaultValue("documents") String kekId,
        @DefaultValue("1536") int embeddingDimension,
        @DefaultValue("1000") int chunkMaxChars,
        @DefaultValue("150") int chunkOverlapChars,
        @DefaultValue("5") int topK,
        @DefaultValue("0.30") double minSimilarity,
        @DefaultValue("0.995") double cacheSimilarity,
        @DefaultValue("1000") int maxMessageChars,
        @DefaultValue("20971520") int maxTextLayerBytes,
        @DefaultValue("5000") int maxChunksPerDocument,
        @DefaultValue("64") int embeddingBatchSize,
        @DefaultValue("30s") Duration llmTimeout,
        @DefaultValue("false") boolean migrateOnStartup,
        @DefaultValue Relay relay,
        @DefaultValue Llm llm,
        @DefaultValue Limits limits) {

    public ChatProperties {
        if (embeddingDimension < 1 || embeddingDimension > 2000) {
            throw new IllegalArgumentException("idp.chat.embedding-dimension debe estar entre 1 y 2000 (HNSW)");
        }
        if (chunkOverlapChars < 0 || chunkOverlapChars >= chunkMaxChars) {
            throw new IllegalArgumentException("idp.chat.chunk-overlap-chars debe ser menor que chunk-max-chars");
        }
    }

    /**
     * Modelos fijados por configuracion (SEC-049: nada de "latest" implicito) y proteccion del proveedor: tope de
     * concurrencia por tenant (bulkhead; saturado = 503 con Retry-After) y modelo secundario opcional (failover).
     */
    public record Llm(@DefaultValue("gpt-4o-mini-2024-07-18") String model,
                      @DefaultValue("text-embedding-3-small") String embeddingModel,
                      @DefaultValue("") String fallbackModel,
                      @DefaultValue("4") int bulkheadMaxConcurrent,
                      @DefaultValue("0s") Duration bulkheadMaxWait,
                      @DefaultValue("5s") Duration retryAfter,
                      @DefaultValue("1000") int embeddingCacheSize) {
        public Llm {
            if (bulkheadMaxConcurrent < 1) {
                throw new IllegalArgumentException("idp.chat.llm.bulkhead-max-concurrent debe ser >= 1");
            }
        }
    }

    /**
     * Limites de uso (SEC-049, ADR 0023): peticiones por minuto por (tenant, usuario) y por tenant, y tope diario de
     * tokens del LLM por tenant (dia UTC). Un valor <= 0 en tokens diarios desactiva el tope (solo para entornos de
     * prueba); los limites de tasa deben ser >= 1.
     */
    public record Limits(@DefaultValue("20") int userPerMinute,
                         @DefaultValue("300") int tenantPerMinute,
                         @DefaultValue("2000000") long dailyTokens) {
        public Limits {
            if (userPerMinute < 1 || tenantPerMinute < 1) {
                throw new IllegalArgumentException("idp.chat.limits.*-per-minute debe ser >= 1");
            }
        }
    }

    public record Relay(@DefaultValue("true") boolean enabled, @DefaultValue("1s") Duration interval) {
    }
}
