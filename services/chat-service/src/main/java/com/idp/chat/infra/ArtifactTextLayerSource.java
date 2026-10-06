package com.idp.chat.infra;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.chat.service.TextChunker.Page;
import com.idp.events.EventValidationException;
import com.idp.kms.EnvelopeCrypto;
import com.idp.storage.ArtifactKind;
import com.idp.storage.EncryptedArtifactStore;
import com.idp.storage.ObjectStore;
import com.idp.tenant.context.TenantKeyResolver;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Lee {@code documents/{id}/text_layer.json.enc} (ruta y AAD de libs/storage-port, ArtifactKind.TEXT_LAYER) con la
 * forma {@code {"pages":[{"page":n,"text":"..."}]}}. El contenido es JSON UTF-8.
 */
public class ArtifactTextLayerSource implements TextLayerSource {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final EncryptedArtifactStore store;
    private final int maxEncryptedBytes;

    public ArtifactTextLayerSource(ObjectStore objects, EnvelopeCrypto crypto, TenantKeyResolver keys,
                                   int maxEncryptedBytes) {
        this.store = new EncryptedArtifactStore(objects, crypto, keys);
        this.maxEncryptedBytes = maxEncryptedBytes;
    }

    @Override
    public Optional<List<Page>> pages(String tenantId, UUID documentId) {
        return store.find(tenantId, documentId, ArtifactKind.TEXT_LAYER, 0, maxEncryptedBytes).map(ArtifactTextLayerSource::parse);
    }

    static List<Page> parse(byte[] json) {
        JsonNode root;
        try {
            root = MAPPER.readTree(json);
        } catch (IOException e) {
            throw new EventValidationException("Capa de texto ilegible", e);
        }
        JsonNode pages = root.path("pages");
        if (!pages.isArray()) {
            throw new EventValidationException("Capa de texto sin arreglo pages");
        }
        List<Page> out = new ArrayList<>();
        for (JsonNode p : pages) {
            int number = p.path("page").asInt(0);
            if (number < 1 || !p.path("text").isTextual()) {
                throw new EventValidationException("Pagina de capa de texto invalida");
            }
            out.add(new Page(number, p.path("text").asText()));
        }
        return out;
    }
}
