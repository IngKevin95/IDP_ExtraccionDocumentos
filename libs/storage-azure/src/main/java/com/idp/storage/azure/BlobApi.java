package com.idp.storage.azure;

import java.io.InputStream;
import java.time.Instant;

/**
 * Interfaz fina sobre Azure Blob (ADR 0032): lo minimo que necesitan los adaptadores. La implementacion real usa
 * el SDK; los tests de WORM usan un fake porque Azurite no cubre immutability policies (plan T-00).
 */
public interface BlobApi {

    /** El blob no existe. */
    class NotFoundException extends RuntimeException {
        public NotFoundException() {
            super("blob no encontrado");
        }
    }

    /**
     * Escribe el blob de forma atomica. Si {@code sha256} no es nulo y no coincide con el contenido, o la longitud
     * leida difiere de {@code length}, falla sin dejar el blob visible. Con {@code retainUntil} el blob queda con
     * immutability policy por version.
     */
    void write(String container, String blob, InputStream data, long length, String contentType, byte[] sha256,
               Instant retainUntil);

    InputStream read(String container, String blob);

    void delete(String container, String blob);

    /** Legal hold sobre la version actual del blob. */
    void setLegalHold(String container, String blob, boolean hold);

    /** True si el contenedor tiene immutable storage con versionado habilitado. */
    boolean versionLevelWormEnabled(String container);
}
