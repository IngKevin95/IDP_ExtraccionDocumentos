package com.idp.extraction.store;

import java.util.List;
import java.util.stream.Collectors;

/** Paginas de un documento renderizado (PNG + texto nativo por pagina). */
public record RenderedDocument(List<PageContent> pages) {

    public RenderedDocument {
        pages = List.copyOf(pages);
    }

    /** Capa de texto nativa concatenada; vacia si ninguna pagina la tiene. */
    public String nativeText() {
        return pages.stream().map(PageContent::nativeText).filter(t -> t != null && !t.isBlank())
            .collect(Collectors.joining("\n"));
    }

    public boolean hasTextLayer() {
        return !nativeText().isBlank();
    }
}
