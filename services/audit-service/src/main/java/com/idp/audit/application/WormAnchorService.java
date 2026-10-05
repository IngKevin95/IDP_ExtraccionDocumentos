package com.idp.audit.application;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.audit.domain.AuditEntry;
import com.idp.audit.domain.LegalHoldRecord;
import com.idp.audit.domain.WormAnchor;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.audit.infrastructure.AuditRepository.Head;
import com.idp.kms.KeyService;
import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectMetadata;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Anclaje por lote de la cadena en el ImmutableStore (Object Lock compliance, SEC-021): manifiesto JSON
 * comprimido con la cabeza de cadena del lote, firmado con la llave asimetrica de auditoria (separada de las
 * KEK; la llave privada nunca sale del KeyService). Corre bajo el bloqueo de cabeza del tenant, por lo que no
 * compite con la ingesta ni con otra replica (AC-03).
 */
@Service
public class WormAnchorService {

    public static final String SIGNATURE_ALGORITHM = "ed25519";

    private final AuditRepository repo;
    private final ImmutableStore store;
    private final KeyService keys;
    private final Clock clock;
    private final AuditMetrics metrics;
    private final com.idp.tenant.context.TenantKeyResolver keyResolver;
    private final Duration retention;
    private final int batchSize;
    private final Duration maxAge;

    public WormAnchorService(AuditRepository repo, ImmutableStore store, KeyService keys, Clock clock,
                             AuditMetrics metrics,
                             com.idp.tenant.context.TenantKeyResolver keyResolver,
                             @Value("${idp.audit.worm.retention-days:3650}") long retentionDays,
                             @Value("${idp.audit.anchor.batch-size:1000}") int batchSize,
                             @Value("${idp.audit.anchor.max-age-hours:1}") long maxAgeHours) {
        this.repo = repo;
        this.store = store;
        this.keys = keys;
        this.clock = clock;
        this.metrics = metrics;
        this.keyResolver = keyResolver;
        this.retention = Duration.ofDays(retentionDays);
        this.batchSize = batchSize;
        this.maxAge = Duration.ofHours(maxAgeHours);
    }

    /** Ancla si hay {@code batch-size} pendientes o el mas antiguo supera {@code max-age-hours}. */
    @Transactional
    public Optional<WormAnchor> anchorIfDue(UUID tenantId) {
        return anchor(tenantId, false);
    }

    /** Fuerza el anclaje de lo pendiente (trigger manual / cierre de dia). */
    @Transactional
    public Optional<WormAnchor> anchorNow(UUID tenantId) {
        return anchor(tenantId, true);
    }

    private Optional<WormAnchor> anchor(UUID tenantId, boolean force) {
        Optional<Head> head = repo.lockHead(tenantId);
        if (head.isEmpty()) {
            return Optional.empty();
        }
        List<AuditEntry> batch = repo.findUnanchored(tenantId, batchSize);
        metrics.unanchored(tenantId, repo.countUnanchored(tenantId));
        if (batch.isEmpty()) {
            return Optional.empty();
        }
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        boolean full = batch.size() >= batchSize;
        boolean old = batch.get(0).createdAt().plus(maxAge).isBefore(now);
        if (!force && !full && !old) {
            return Optional.empty();
        }
        long start = batch.get(0).sequenceId();
        long end = batch.get(batch.size() - 1).sequenceId();
        if (end - start + 1 != batch.size()) {
            throw new IllegalStateException("Hueco en el lote a anclar del tenant " + tenantId);
        }
        Optional<WormAnchor> previous = repo.lastAnchor(tenantId);
        if (previous.isPresent() && previous.get().endSequenceId() + 1 != start) {
            throw new IllegalStateException("El lote no es contiguo al anclaje previo del tenant " + tenantId);
        }

        ObjectNode manifest = manifest(tenantId, batch, start, end, previous.map(WormAnchor::manifestHash));
        byte[] manifestBytes = CanonicalJson.bytes(manifest);
        String manifestHash = HashChainService.sha256Hex(manifestBytes);
        TenantId tenant = new TenantId(tenantId.toString());
        String signingKeyId = keyResolver.resolve(tenantId.toString()).auditKekId();
        String signature = Base64.getEncoder()
                .encodeToString(keys.sign(tenant, manifestBytes, signingKeyId).getData());

        ObjectNode doc = CanonicalJson.mapper().createObjectNode();
        doc.put("manifestHash", manifestHash);
        ObjectNode sig = doc.putObject("signature");
        sig.put("algorithm", SIGNATURE_ALGORITHM);
        sig.put("keyId", signingKeyId);
        sig.put("value", signature);
        doc.set("manifest", manifest);
        byte[] gz = gzip(CanonicalJson.bytes(doc));

        String path = String.format("audit/anchors/%019d-%019d.json.gz", start, end);
        store.putWithRetention(tenant, path, new ByteArrayInputStream(gz),
                new ObjectMetadata(HashChainService.sha256Hex(gz), gz.length, "application/gzip"), retention);
        if (holdCovers(repo.activeHolds(tenantId), batch)) {
            store.applyLegalHold(tenant, path);
        }

        WormAnchor anchor = new WormAnchor(UUID.randomUUID(), tenantId, start, end, path, manifestHash, signature,
                now);
        repo.insertAnchor(anchor);
        repo.markAnchored(tenantId, start, end);
        metrics.unanchored(tenantId, repo.countUnanchored(tenantId));
        return Optional.of(anchor);
    }

    /** Hold de tenant completo, o de un documento presente en el lote, extiende la proteccion al ancla. */
    static boolean holdCovers(List<LegalHoldRecord> holds, List<AuditEntry> batch) {
        return holds.stream().anyMatch(h -> h.documentId() == null
                || batch.stream().anyMatch(e -> h.documentId().equals(e.documentId())));
    }

    private static ObjectNode manifest(UUID tenantId, List<AuditEntry> batch, long start, long end,
                                       Optional<String> previousManifestHash) {
        ObjectNode m = CanonicalJson.mapper().createObjectNode();
        m.put("tenantId", tenantId.toString());
        m.put("startSequenceId", start);
        m.put("endSequenceId", end);
        m.put("headHash", batch.get(batch.size() - 1).currentHash());
        m.put("previousManifestHash", previousManifestHash.orElse(null));
        ArrayNode entries = m.putArray("entries");
        for (AuditEntry e : batch) {
            ObjectNode n = entries.addObject();
            n.put("sequenceId", e.sequenceId());
            n.put("eventId", e.eventId().toString());
            n.put("eventType", e.eventType());
            n.put("documentId", e.documentId() == null ? null : e.documentId().toString());
            n.put("occurredAt", e.occurredAt().toString());
            n.put("payloadSha256", e.payloadSha256());
            n.put("previousHash", e.previousHash());
            n.put("currentHash", e.currentHash());
        }
        return m;
    }

    private static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
