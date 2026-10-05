package com.idp.extraction.store;

import com.idp.tenant.TenantId;
import java.util.UUID;

/** Puerto de lectura del documento renderizado desde el bucket del tenant. */
public interface DocumentStoragePort {

    /** @throws DocumentNotRenderedException si el documento no tiene paginas renderizadas */
    RenderedDocument load(TenantId tenant, UUID documentId);

    class DocumentNotRenderedException extends RuntimeException {
        public DocumentNotRenderedException(String message) {
            super(message);
        }
    }
}
