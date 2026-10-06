package com.idp.review.infra;

import java.util.Optional;
import java.util.UUID;

/** Puerto: PNG renderizado de una pagina del documento (1-based), descifrado, o vacio si no existe. */
public interface PageImageSource {

    Optional<byte[]> page(String tenantId, UUID documentId, int page);
}
