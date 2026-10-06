package com.idp.chat.infra;

import com.idp.chat.service.TextChunker.Page;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Capa de texto nativa de un documento (puerto sobre el almacen de artefactos del tenant). */
public interface TextLayerSource {

    /** Paginas con texto, o vacio si el artefacto no existe. Un artefacto ilegible lanza excepcion. */
    Optional<List<Page>> pages(String tenantId, UUID documentId);
}
