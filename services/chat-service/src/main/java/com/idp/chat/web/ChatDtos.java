package com.idp.chat.web;

import com.idp.chat.domain.ChatAnswer;
import com.idp.chat.domain.ChatSession;
import com.idp.chat.domain.Citation;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** DTOs de la API (contracts/openapi/chat-service.yaml). */
public final class ChatDtos {

    private ChatDtos() {
    }

    public record CreateSessionRequest(@NotNull UUID documentId) {
    }

    public record ChatSessionResponse(UUID id, UUID documentId, OffsetDateTime createdAt) {
        public static ChatSessionResponse of(ChatSession s) {
            return new ChatSessionResponse(s.id(), s.documentId(), s.createdAt());
        }
    }

    public record SendMessageRequest(@NotBlank @Size(max = 1000) @Pattern(regexp = "[^\u0000]*") String content) {
    }

    public record CitationResponse(UUID chunkId, int pageNumber, String exactQuote) {
        static CitationResponse of(Citation c) {
            return new CitationResponse(c.chunkId(), c.pageNumber(), c.exactQuote());
        }
    }

    public record ChatMessageResponse(UUID id, String role, String content, List<CitationResponse> citations) {
        public static ChatMessageResponse of(ChatAnswer a) {
            return new ChatMessageResponse(a.messageId(), "assistant", a.content(),
                    a.citations().stream().map(CitationResponse::of).toList());
        }
    }

    /** Cuerpo de error del contrato: code, message e incidentId (correlacion con trazas y eventos de seguridad). */
    public record ErrorResponse(String code, String message, UUID incidentId) {
        public static ErrorResponse of(String code, String message) {
            return new ErrorResponse(code, message, UUID.randomUUID());
        }
    }
}
