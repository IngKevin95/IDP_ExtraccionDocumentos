package com.idp.chat.domain;

import java.util.UUID;

/** Fragmento de la capa de texto de un documento. {@code similarity} = 1 - distancia coseno (0 si no viene de busqueda). */
public record Chunk(UUID id, UUID documentId, int ordinal, int pageNumber, String content, double similarity) {
}
