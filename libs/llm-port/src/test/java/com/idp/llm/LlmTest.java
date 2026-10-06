package com.idp.llm;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import com.idp.tenant.TenantId;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmTest {

    @Test
    void embedDelegaEnEmbedBatchYDevuelveElPrimerVector() {
        EmbeddingProvider provider = new EmbeddingProvider() {
            @Override
            public int dimension() {
                return 2;
            }

            @Override
            public List<float[]> embedBatch(TenantId tenantId, List<String> texts) {
                return texts.stream().map(t -> new float[] {t.length(), 1f}).toList();
            }
        };

        assertArrayEquals(new float[] {3f, 1f}, provider.embed(new TenantId("t"), "abc"));
        assertEquals(2, provider.dimension());
    }
}
