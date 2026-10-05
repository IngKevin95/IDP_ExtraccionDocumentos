package com.idp.extraction.store;

import com.idp.storage.ObjectStore;
import com.idp.tenant.TenantId;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Lee PNG y texto nativo del bucket del tenant via {@link ObjectStore}. Convencion de claves
 * (configurable): {@code documents/<id>/pages/page_<n>.png} y {@code documents/<id>/text/page_<n>.txt}
 * (el texto es opcional por pagina).
 */
public final class S3DocumentStorageAdapter implements DocumentStoragePort {

    static final String NOT_FOUND_MESSAGE = "Objeto no encontrado";
    private static final int MAX_PNG_BYTES = 25 * 1024 * 1024;
    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;

    private final ObjectStore store;
    private final String pageTemplate;
    private final String textTemplate;
    private final int maxPages;

    public S3DocumentStorageAdapter(ObjectStore store, String pageTemplate, String textTemplate, int maxPages) {
        this.store = store;
        this.pageTemplate = pageTemplate;
        this.textTemplate = textTemplate;
        this.maxPages = maxPages;
    }

    @Override
    public RenderedDocument load(TenantId tenant, UUID documentId) {
        List<PageContent> pages = new ArrayList<>();
        for (int n = 1; n <= maxPages; n++) {
            byte[] png = read(tenant, pageTemplate.formatted(documentId, n), MAX_PNG_BYTES, n > 1);
            if (png == null) {
                break;
            }
            byte[] text = read(tenant, textTemplate.formatted(documentId, n), MAX_TEXT_BYTES, true);
            pages.add(new PageContent(n, png, text == null ? null : new String(text, StandardCharsets.UTF_8)));
        }
        if (pages.isEmpty()) {
            throw new DocumentNotRenderedException("Documento sin paginas renderizadas");
        }
        return new RenderedDocument(pages);
    }

    /** Lee un objeto; devuelve null si no existe y {@code optional}. */
    private byte[] read(TenantId tenant, String path, int limit, boolean optional) {
        try (InputStream in = store.get(tenant, path)) {
            byte[] data = in.readNBytes(limit + 1);
            if (data.length > limit) {
                throw new IllegalStateException("Objeto excede el tamano maximo permitido");
            }
            return data;
        } catch (ObjectStore.StorageException e) {
            if (NOT_FOUND_MESSAGE.equals(e.getMessage())) {
                if (optional) {
                    return null;
                }
                throw new DocumentNotRenderedException("Documento sin paginas renderizadas");
            }
            throw e;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
