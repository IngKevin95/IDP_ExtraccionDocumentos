package com.idp.audit.application;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.audit.domain.AuditEntry;
import com.idp.audit.domain.Exceptions.ChainIntegrityException;
import com.idp.audit.domain.Exceptions.NotFoundException;
import com.idp.audit.domain.WormAnchor;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.kms.KeyService;
import com.idp.tenant.TenantId;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Expediente forense firmado de un documento (RF-602, SEC-042). Verifica la cadena de cada evento antes de
 * exportar y firma el compilado canonico con la llave asimetrica de auditoria via KeyService. Si el documento
 * fue purgado (documento.purgado) los payloads de los demas eventos se ofuscan, pero cada evento conserva su
 * payloadSha256 y sus hashes: la cadena sigue siendo matematicamente verificable (AC-07).
 */
@Service
public class DossierService {

    public static final String PURGE_EVENT = "documento.purgado";

    /** Resultado de la verificacion publica de un expediente. */
    public record SignatureCheck(boolean valid, boolean signatureValid, boolean eventHashesValid) {}

    private final AuditRepository repo;
    private final KeyService keys;
    private final Clock clock;
    private final AuditMetrics metrics;
    private final AuditAlertService alerts;
    private final String signingKeyId;

    public DossierService(AuditRepository repo, KeyService keys, Clock clock, AuditMetrics metrics,
                          AuditAlertService alerts,
                          @Value("${idp.audit.signing-key-id:audit-signing}") String signingKeyId) {
        this.repo = repo;
        this.keys = keys;
        this.clock = clock;
        this.metrics = metrics;
        this.alerts = alerts;
        this.signingKeyId = signingKeyId;
    }

    /** Devuelve el expediente firmado como JSON. El tenant llega siempre del JWT revalidado. */
    @Transactional(readOnly = true)
    public String export(UUID tenantId, UUID documentId) {
        return metrics.dossierTimer().record(() -> build(tenantId, documentId));
    }

    private String build(UUID tenantId, UUID documentId) {
        List<AuditEntry> entries = repo.findByDocument(tenantId, documentId);
        if (entries.isEmpty()) {
            throw new NotFoundException("No se encontro el expediente del documento solicitado.");
        }
        verifyEntries(tenantId, entries);
        boolean purged = entries.stream().anyMatch(e -> PURGE_EVENT.equals(e.eventType()));
        long min = entries.get(0).sequenceId();
        long max = entries.get(entries.size() - 1).sequenceId();
        List<WormAnchor> anchors = repo.anchorsCovering(tenantId, min, max);

        ObjectNode d = CanonicalJson.mapper().createObjectNode();
        d.put("dossierId", UUID.randomUUID().toString());
        d.put("tenantId", tenantId.toString());
        d.put("documentId", documentId.toString());
        d.put("generatedAt", clock.instant().toString());
        d.put("totalEvents", entries.size());
        d.put("purged", purged);
        ArrayNode events = d.putArray("events");
        for (AuditEntry e : entries) {
            events.add(eventNode(e, purged && !PURGE_EVENT.equals(e.eventType())));
        }
        ArrayNode anchorNodes = d.putArray("anchors");
        for (WormAnchor a : anchors) {
            ObjectNode n = anchorNodes.addObject();
            n.put("anchorId", a.anchorId().toString());
            n.put("startSequenceId", a.startSequenceId());
            n.put("endSequenceId", a.endSequenceId());
            n.put("fileUri", a.fileUri());
            n.put("manifestHash", a.manifestHash());
            n.put("signature", a.signature());
        }
        d.put("unanchoredEvents", entries.stream().filter(e -> !e.wormAnchored()).count());

        byte[] signature = keys.sign(new TenantId(tenantId.toString()), CanonicalJson.bytes(d), signingKeyId)
                .getData();
        ObjectNode sig = d.putObject("signature");
        sig.put("algorithm", WormAnchorService.SIGNATURE_ALGORITHM);
        sig.put("keyId", signingKeyId);
        sig.put("signatureValue", Base64.getEncoder().encodeToString(signature));
        try {
            return CanonicalJson.mapper().writeValueAsString(d);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Expediente no serializable", e);
        }
    }

    private void verifyEntries(UUID tenantId, List<AuditEntry> entries) {
        Map<Long, String> previous = repo.hashesBySequence(tenantId,
                entries.stream().map(e -> e.sequenceId() - 1).filter(s -> s > 0).toList());
        for (AuditEntry e : entries) {
            String expectedPrev = e.sequenceId() == 1 ? HashChainService.GENESIS : previous.get(e.sequenceId() - 1);
            String problem = expectedPrev == null
                    ? "Falta el registro previo en la cadena"
                    : AuditVerificationService.entryProblem(e, expectedPrev);
            if (problem != null) {
                String type = expectedPrev == null ? "SEQUENCE_GAP" : "HASH_MISMATCH";
                alerts.integrityAlert(tenantId, e.sequenceId(), type, problem);
                throw new ChainIntegrityException(tenantId, e.sequenceId(), type, problem);
            }
        }
    }

    private static ObjectNode eventNode(AuditEntry e, boolean redact) {
        ObjectNode n = CanonicalJson.mapper().createObjectNode();
        n.put("sequenceId", e.sequenceId());
        n.put("eventId", e.eventId().toString());
        n.put("eventType", e.eventType());
        n.put("correlationId", e.correlationId() == null ? null : e.correlationId().toString());
        n.put("occurredAt", e.occurredAt().toString());
        n.put("actorId", e.actorId());
        n.put("currentHash", e.currentHash());
        n.put("previousHash", e.previousHash());
        n.put("payloadSha256", e.payloadSha256());
        n.put("redacted", redact);
        if (redact) {
            ObjectNode p = n.putObject("payload");
            p.put("redacted", "documento.purgado");
        } else {
            n.set("payload", e.payload());
        }
        return n;
    }

    /**
     * Verificacion publica: valida la firma ed25519 del expediente y recalcula el hash de cada evento con los
     * campos exportados. No consulta datos del tenant; solo usa la llave de verificacion.
     */
    public SignatureCheck verifySignature(JsonNode dossier) {
        boolean signatureValid = false;
        boolean hashesValid = false;
        try {
            UUID tenantId = UUID.fromString(dossier.path("tenantId").asText());
            JsonNode sig = dossier.path("signature");
            if (signingKeyId.equals(sig.path("keyId").asText())
                    && WormAnchorService.SIGNATURE_ALGORITHM.equals(sig.path("algorithm").asText())) {
                ObjectNode unsigned = dossier.deepCopy();
                unsigned.remove("signature");
                byte[] value = Base64.getDecoder().decode(sig.path("signatureValue").asText());
                signatureValid = keys.verify(new TenantId(tenantId.toString()), CanonicalJson.bytes(unsigned),
                        value, signingKeyId);
            }
            hashesValid = eventHashesValid(tenantId, dossier);
        } catch (RuntimeException e) {
            // Expediente malformado o llave inexistente: no es valido.
            return new SignatureCheck(false, signatureValid, hashesValid);
        }
        return new SignatureCheck(signatureValid && hashesValid, signatureValid, hashesValid);
    }

    private static boolean eventHashesValid(UUID tenantId, JsonNode dossier) {
        JsonNode events = dossier.path("events");
        if (!events.isArray() || events.isEmpty()) {
            return false;
        }
        String documentId = dossier.path("documentId").asText(null);
        for (JsonNode e : events) {
            boolean redacted = e.path("redacted").asBoolean(false);
            if (!redacted && !HashChainService.payloadSha256(e.path("payload")).equals(
                    e.path("payloadSha256").asText())) {
                return false;
            }
            String canonical = HashChainService.canonicalEvent(tenantId, UUID.fromString(e.path("eventId").asText()),
                    e.path("eventType").asText(), uuidOrNull(e.path("correlationId").asText(null)),
                    uuidOrNull(documentId), e.path("actorId").asText(null),
                    Instant.parse(e.path("occurredAt").asText()), e.path("payloadSha256").asText());
            String hash = HashChainService.hash(e.path("previousHash").asText(), e.path("sequenceId").asLong(),
                    canonical);
            if (!hash.equals(e.path("currentHash").asText())) {
                return false;
            }
        }
        return true;
    }

    private static UUID uuidOrNull(String v) {
        return v == null ? null : UUID.fromString(v);
    }
}
