package com.idp.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.audit.domain.AuditEntry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Calculo de la hash-chain: {@code hash = SHA-256(prev_hash || seq || evento canonico)}. El evento canonico
 * incluye el SHA-256 del payload (no el payload), de modo que un expediente con payload ofuscado por purga
 * conserva la validez matematica de la cadena (AC-07).
 */
public final class HashChainService {

    /** Hash previo del primer registro de cada cadena. */
    public static final String GENESIS = "0".repeat(64);

    private HashChainService() {
    }

    public static String sha256Hex(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 no disponible", e);
        }
    }

    public static String payloadSha256(JsonNode payload) {
        return sha256Hex(CanonicalJson.bytes(payload));
    }

    public static String canonicalEvent(UUID tenantId, UUID eventId, String eventType, UUID correlationId,
                                        UUID documentId, String actorId, Instant occurredAt, String payloadSha256) {
        ObjectNode n = CanonicalJson.mapper().createObjectNode();
        n.put("tenantId", tenantId.toString());
        n.put("eventId", eventId.toString());
        n.put("eventType", eventType);
        n.put("correlationId", correlationId == null ? null : correlationId.toString());
        n.put("documentId", documentId == null ? null : documentId.toString());
        n.put("actorId", actorId);
        n.put("occurredAt", occurredAt.toString());
        n.put("payloadSha256", payloadSha256);
        return CanonicalJson.write(n);
    }

    public static String hash(String previousHash, long sequenceId, String canonicalEvent) {
        return sha256Hex((previousHash + sequenceId + canonicalEvent).getBytes(StandardCharsets.UTF_8));
    }

    /** Recalcula el hash esperado de un registro persistido con su previous_hash y su payload actuales. */
    public static String recompute(AuditEntry e) {
        String canonical = canonicalEvent(e.tenantId(), e.eventId(), e.eventType(), e.correlationId(),
                e.documentId(), e.actorId(), e.occurredAt(), payloadSha256(e.payload()));
        return hash(e.previousHash(), e.sequenceId(), canonical);
    }
}
