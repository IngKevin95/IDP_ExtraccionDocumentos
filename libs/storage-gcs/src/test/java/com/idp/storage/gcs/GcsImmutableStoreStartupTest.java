package com.idp.storage.gcs;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** AC-09: el adaptador WORM falla al arrancar si el bucket no tiene retencion por objeto. */
class GcsImmutableStoreStartupTest {

    @Test
    void bucketSinRetencionPorObjetoImpideElArranque() {
        InMemoryGcsBlobApi api = new InMemoryGcsBlobApi();
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> new GcsImmutableStore(api, "sin-lock"));
        assertTrue(ex.getMessage().contains("retencion por objeto"));
    }

    @Test
    void fallaDelProveedorAlValidarImpideElArranque() {
        InMemoryGcsBlobApi api = new InMemoryGcsBlobApi().withObjectRetention("worm");
        api.failing = true;
        assertThrows(IllegalStateException.class, () -> new GcsImmutableStore(api, "worm"));
    }

    @Test
    void bucketConRetencionPorObjetoArranca() {
        InMemoryGcsBlobApi api = new InMemoryGcsBlobApi().withObjectRetention("worm");
        assertDoesNotThrow(() -> new GcsImmutableStore(api, "worm"));
    }
}
