package com.idp.storage.gcs;

import java.time.Instant;

/**
 * Interfaz fina sobre Cloud Storage: lo minimo que necesitan los adaptadores. Permite probar la semantica WORM con
 * un fake, porque los emuladores no implementan retencion por objeto ni holds (multicloud plan, spike T-00).
 * Los mensajes de sus excepciones no incluyen rutas ni credenciales.
 */
public interface GcsBlobApi {

    class BlobApiException extends RuntimeException {
        public BlobApiException(String message) {
            super(message);
        }
    }

    /** Objeto o bucket inexistente (404). Es un {@link BlobApiException}: cualquier fallo del proveedor es uno. */
    class BlobNotFoundException extends BlobApiException {
        public BlobNotFoundException() {
            super("recurso no encontrado");
        }
    }

    /** Crea o sobrescribe; con {@code retainUntil} aplica retencion por objeto en modo bloqueado. */
    void write(String bucket, String key, byte[] data, String contentType, Instant retainUntil);

    byte[] read(String bucket, String key);

    /** Idempotente si el objeto no existe; falla si hay retencion vigente o hold temporal. */
    void delete(String bucket, String key);

    void setTemporaryHold(String bucket, String key, boolean hold);

    /** {@code true} solo si el bucket existe y tiene habilitada la retencion por objeto. */
    boolean objectRetentionEnabled(String bucket);
}
