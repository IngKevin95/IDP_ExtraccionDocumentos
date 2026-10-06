package com.idp.chat.domain;

import java.util.List;
import java.util.UUID;

/** Respuesta del asistente ya persistida. */
public record ChatAnswer(UUID messageId, String content, Outcome outcome, List<Citation> citations) {
    public ChatAnswer {
        citations = List.copyOf(citations);
    }
}
