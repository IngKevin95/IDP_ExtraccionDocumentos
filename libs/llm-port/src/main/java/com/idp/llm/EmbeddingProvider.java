package com.idp.llm;

import com.idp.tenant.TenantId;
import java.util.List;

/**
 * Puerto de embeddings. La dimension del modelo es contrato: el consumidor la valida contra el esquema vectorial del
 * silo del tenant y falla cerrado si difiere.
 */
public interface EmbeddingProvider {

    /** Dimension de los vectores que produce el modelo configurado. */
    int dimension();

    /** Un vector por texto, en el mismo orden. */
    List<float[]> embedBatch(TenantId tenantId, List<String> texts);

    default float[] embed(TenantId tenantId, String text) {
        return embedBatch(tenantId, List.of(text)).get(0);
    }
}
