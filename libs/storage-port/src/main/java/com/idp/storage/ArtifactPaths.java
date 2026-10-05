package com.idp.storage;

import java.util.UUID;

/** Rutas canonicas de los artefactos de un documento en el bucket del tenant (productor y consumidores). */
public final class ArtifactPaths {

    private ArtifactPaths() {
    }

    public static String prefix(UUID documentId) {
        return "documents/" + documentId + "/";
    }

    public static String original(UUID documentId) {
        return prefix(documentId) + "original.enc";
    }

    public static String page(UUID documentId, int page) {
        return prefix(documentId) + "pages/page_" + page + ".png.enc";
    }

    /** Capa de texto nativo de todo el documento: {@code {"pages":[{"page":n,"text":"..."}]}}. */
    public static String textLayer(UUID documentId) {
        return prefix(documentId) + "text_layer.json.enc";
    }

    /** Ruta del artefacto; {@code page} solo aplica a {@link ArtifactKind#PAGE_PNG}. */
    public static String key(ArtifactKind kind, UUID documentId, int page) {
        return switch (kind) {
            case ORIGINAL -> original(documentId);
            case PAGE_PNG -> page(documentId, page);
            case TEXT_LAYER -> textLayer(documentId);
        };
    }
}
