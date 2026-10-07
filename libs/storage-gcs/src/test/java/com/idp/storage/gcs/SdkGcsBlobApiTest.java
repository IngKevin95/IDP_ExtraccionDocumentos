package com.idp.storage.gcs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Bucket;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Verifica que el adaptador real pide al SDK retencion LOCKED, hold temporal y la lectura de retencion del bucket. */
class SdkGcsBlobApiTest {

    private final Storage storage = mock(Storage.class);
    private final SdkGcsBlobApi api = new SdkGcsBlobApi(storage);

    @Test
    void escrituraConRetencionUsaModoBloqueadoYFechaDeRetencion() {
        Instant until = Instant.parse("2030-01-01T00:00:00Z");
        api.write("b", "k", new byte[] {1}, "text/plain", until);

        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(info.capture(), any(byte[].class));
        assertThat(info.getValue().getRetention().getMode()).isEqualTo(BlobInfo.Retention.Mode.LOCKED);
        assertThat(info.getValue().getRetention().getRetainUntilTime().toInstant()).isEqualTo(until);
        assertThat(info.getValue().getContentType()).isEqualTo("text/plain");
    }

    @Test
    void escrituraSinRetencionNoLaDeclara() {
        api.write("b", "k", new byte[] {1}, null, null);
        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage).create(info.capture(), any(byte[].class));
        assertThat(info.getValue().getRetention()).isNull();
    }

    @Test
    void holdTemporalSeAplicaYSeQuitaConUpdate() {
        api.setTemporaryHold("b", "k", true);
        api.setTemporaryHold("b", "k", false);
        ArgumentCaptor<BlobInfo> info = ArgumentCaptor.forClass(BlobInfo.class);
        verify(storage, org.mockito.Mockito.times(2)).update(info.capture());
        assertThat(info.getAllValues().get(0).getTemporaryHold()).isTrue();
        assertThat(info.getAllValues().get(1).getTemporaryHold()).isFalse();
        assertThat(info.getAllValues().get(0).getBlobId()).isEqualTo(BlobId.of("b", "k"));
    }

    @Test
    void retencionPorObjetoDelBucketSeLeeDelModo() {
        Bucket enabled = mock(Bucket.class);
        when(enabled.getObjectRetention()).thenReturn(
            BucketInfo.ObjectRetention.newBuilder().setMode(BucketInfo.ObjectRetention.Mode.ENABLED).build());
        Bucket noRetention = mock(Bucket.class);
        when(storage.get("on")).thenReturn(enabled);
        when(storage.get("off")).thenReturn(noRetention);

        assertTrue(api.objectRetentionEnabled("on"));
        assertFalse(api.objectRetentionEnabled("off"));
        assertFalse(api.objectRetentionEnabled("inexistente"));
    }

    @Test
    void erroresDelSdkSeTraducenSinElMensajeOriginal() {
        when(storage.readAllBytes(any(BlobId.class))).thenThrow(new StorageException(404, "tenant-secreto/ruta"))
            .thenThrow(new StorageException(503, "tenant-secreto/ruta"));

        assertThrows(GcsBlobApi.BlobNotFoundException.class, () -> api.read("b", "k"));
        GcsBlobApi.BlobApiException ex = assertThrows(GcsBlobApi.BlobApiException.class, () -> api.read("b", "k"));
        assertThat(ex.getMessage()).contains("503").doesNotContain("secreto");
    }
}
