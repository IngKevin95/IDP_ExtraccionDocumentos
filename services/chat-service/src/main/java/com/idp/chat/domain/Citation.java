package com.idp.chat.domain;

import java.util.UUID;

/** Cita verificada: el texto exacto esta contenido en el fragmento {@code chunkId}. */
public record Citation(UUID chunkId, int pageNumber, String exactQuote) {
}
