package com.idp.extraction.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.storage.ArtifactKind;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.tenant.TenantId;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Lee PNG y texto nativo producidos por document-service: artefactos cifrados con sobre en sus rutas canonicas
 * (libs/storage-port {@code ArtifactPaths}). El texto nativo viene en la capa de texto del documento
 * ({@code {"pages":[{"page":n,"text":"..."}]}}) y es opcional.
 */
public final class EncryptedDocumentStorageAdapter implements DocumentStoragePort {

    private static final int MAX_PNG_BYTES = 25 * 1024 * 1024;
    private static final int MAX_TEXT_LAYER_BYTES = 16 * 1024 * 1024;

    private final EncryptedArtifactStore artifacts;
    private final ObjectMapper json;
    private final int maxPages;

    public EncryptedDocumentStorageAdapter(EncryptedArtifactStore artifacts, ObjectMapper json, int maxPages) {
        this.artifacts = artifacts;
        this.json = json;
        this.maxPages = maxPages;
    }

    @Override
    public RenderedDocument load(TenantId tenant, UUID documentId) {
        String t = tenant.value();
        Map<Integer, String> texts = loadTexts(t, documentId);
        List<PageContent> pages = new ArrayList<>();
        for (int n = 1; n <= maxPages; n++) {
            Optional<byte[]> png = artifacts.find(t, documentId, ArtifactKind.PAGE_PNG, n, MAX_PNG_BYTES);
            if (png.isEmpty()) {
                break;
            }
            pages.add(new PageContent(n, png.get(), texts.get(n)));
        }
        if (pages.isEmpty()) {
            throw new DocumentNotRenderedException("Documento sin paginas renderizadas");
        }
        return new RenderedDocument(pages);
    }

    private Map<Integer, String> loadTexts(String tenant, UUID documentId) {
        Optional<byte[]> raw = artifacts.find(tenant, documentId, ArtifactKind.TEXT_LAYER, 0, MAX_TEXT_LAYER_BYTES);
        Map<Integer, String> out = new HashMap<>();
        if (raw.isEmpty()) {
            return out;
        }
        try {
            JsonNode pages = json.readTree(raw.get()).path("pages");
            for (JsonNode p : pages) {
                String text = p.path("text").asText("");
                if (!text.isBlank()) {
                    out.put(p.path("page").asInt(), text);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out;
    }
}
