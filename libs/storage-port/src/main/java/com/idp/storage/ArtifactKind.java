package com.idp.storage;

/** Tipos de artefacto documental guardados cifrados en el bucket del tenant. */
public enum ArtifactKind {
    ORIGINAL("original"),
    PAGE_PNG("page_png"),
    TEXT_LAYER("text_layer");

    private final String code;

    ArtifactKind(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }
}
