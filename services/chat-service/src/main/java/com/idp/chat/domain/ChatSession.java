package com.idp.chat.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

public record ChatSession(UUID id, UUID documentId, String userId, OffsetDateTime createdAt, boolean active) {
}
