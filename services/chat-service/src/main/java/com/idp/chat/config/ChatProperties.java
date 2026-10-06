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
        @DefaultValue("0.98") double cacheSimilarity,
        @DefaultValue("1000") int maxMessageChars,
        @DefaultValue("20971520") int maxTextLayerBytes,
        @DefaultValue("5000") int maxChunksPerDocument,
        @DefaultValue("64") int embeddingBatchSize,
        @DefaultValue("30s") Duration llmTimeout,
        @DefaultValue("false") boolean migrateOnStartup,
        @DefaultValue Relay relay) {

    public ChatProperties {
        if (embeddingDimension < 1 || embeddingDimension > 2000) {
            throw new IllegalArgumentException("idp.chat.embedding-dimension debe estar entre 1 y 2000 (HNSW)");
        }
        if (chunkOverlapChars < 0 || chunkOverlapChars >= chunkMaxChars) {
            throw new IllegalArgumentException("idp.chat.chunk-overlap-chars debe ser menor que chunk-max-chars");
        }
    }

    public record Relay(@DefaultValue("true") boolean enabled, @DefaultValue("1s") Duration interval) {
    }
}
