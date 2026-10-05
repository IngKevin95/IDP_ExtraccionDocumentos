package com.idp.document.domain;

import java.util.UUID;

public record PageArtifact(UUID id, UUID documentId, String kind, int pageNumber, String objectStoreKey) {
    public static final String PAGE_PNG = "PAGE_PNG";
    public static final String TEXT_LAYER = "TEXT_LAYER";
}
