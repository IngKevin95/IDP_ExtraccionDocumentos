package com.idp.chat.service;

import com.idp.chat.config.ChatProperties;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Cache LRU en memoria de embeddings de preguntas, por (tenant, hash de la pregunta normalizada): una pregunta repetida
 * no vuelve a pagar al proveedor. Solo guarda vectores (sin texto); la clave lleva el tenant para no mezclar silos.
 */
@Component
public class EmbeddingCache {

    private final Map<String, float[]> entries;

    public EmbeddingCache(ChatProperties props) {
        int max = Math.max(1, props.llm().embeddingCacheSize());
        this.entries = new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
                return size() > max;
            }
        };
    }

    synchronized float[] get(String tenantId, String hash) {
        float[] v = entries.get(tenantId + ':' + hash);
        return v == null ? null : v.clone();
    }

    synchronized void put(String tenantId, String hash, float[] vector) {
        entries.put(tenantId + ':' + hash, vector.clone());
    }
}
