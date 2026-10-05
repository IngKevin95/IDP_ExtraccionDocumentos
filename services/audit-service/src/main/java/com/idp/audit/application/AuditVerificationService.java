package com.idp.audit.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.idp.audit.domain.AuditEntry;
import com.idp.audit.domain.Exceptions.BadRequestException;
import com.idp.audit.domain.WormAnchor;
import com.idp.audit.infrastructure.AuditRepository;
import com.idp.audit.infrastructure.AuditRepository.Head;
import com.idp.kms.KeyService;
import com.idp.storage.ImmutableStore;
import com.idp.tenant.TenantId;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Verificacion forense (SEC-039/040): recorre la cadena del tenant recalculando la hash-chain, detecta
 * alteracion de payload/hash y huecos de secuencia, y coteja los anclajes WORM (firma, cabeza de cadena y
 * enlace entre manifiestos). Todo hallazgo emite una alerta CRITICA.
 */
@Service
public class AuditVerificationService {

    public record VerificationError(long sequenceId, String errorType, String description) {}

    public record ChainVerificationReport(UUID tenantId, Instant verifiedAt, boolean isChainIntact,
                                          long totalRecordsVerified, int wormAnchorsVerified,
                                          List<VerificationError> errorsFound) {}

    private static final int PAGE = 500;
    private static final int MAX_ALERTS = 20;

    private final AuditRepository repo;
    private final ImmutableStore store;
    private final KeyService keys;
    private final AuditAlertService alerts;
    private final Clock clock;
    private final String signingKeyId;

    public AuditVerificationService(AuditRepository repo, ImmutableStore store, KeyService keys,
                                    AuditAlertService alerts, Clock clock,
                                    @Value("${idp.audit.signing-key-id:audit-signing}") String signingKeyId) {
        this.repo = repo;
        this.store = store;
        this.keys = keys;
        this.alerts = alerts;
        this.clock = clock;
        this.signingKeyId = signingKeyId;
    }

    @Transactional(readOnly = true)
    public ChainVerificationReport verify(UUID tenantId, Long startSequenceId, Long endSequenceId) {
        long start = startSequenceId == null ? 1 : startSequenceId;
        Optional<Head> head = repo.head(tenantId);
        long max = repo.maxSequence(tenantId);
        long end = endSequenceId == null ? max : Math.min(endSequenceId, max);
        if (start < 1 || (endSequenceId != null && endSequenceId < start)) {
            throw new BadRequestException("Rango de secuencias invalido");
        }
        List<VerificationError> errors = new ArrayList<>();
        long verified = verifyChain(tenantId, start, end, errors);
        if (head.isPresent() && end == max) {
            checkHead(tenantId, head.get(), max, errors);
        }
        List<WormAnchor> anchors = repo.anchorsCovering(tenantId, start, Math.max(end, start));
        verifyAnchors(tenantId, repo.anchors(tenantId), anchors, errors);
        errors.stream().limit(MAX_ALERTS).forEach(
                er -> alerts.integrityAlert(tenantId, er.sequenceId(), er.errorType(), er.description()));
        return new ChainVerificationReport(tenantId, clock.instant(), errors.isEmpty(), verified, anchors.size(),
                List.copyOf(errors));
    }

    private long verifyChain(UUID tenantId, long start, long end, List<VerificationError> errors) {
        if (end < start) {
            return 0;
        }
        String expectedPrev = HashChainService.GENESIS;
        if (start > 1) {
            String prev = repo.hashesBySequence(tenantId, List.of(start - 1)).get(start - 1);
            if (prev == null) {
                errors.add(new VerificationError(start - 1, "SEQUENCE_GAP",
                        "No existe el registro previo al rango verificado"));
                expectedPrev = null;
            } else {
                expectedPrev = prev;
            }
        }
        long lastSeq = start - 1;
        long verified = 0;
        long after = start - 1;
        while (true) {
            List<AuditEntry> page = repo.page(tenantId, after, end, PAGE);
            if (page.isEmpty()) {
                break;
            }
            for (AuditEntry e : page) {
                verified++;
                boolean contiguous = e.sequenceId() == lastSeq + 1;
                if (!contiguous) {
                    errors.add(new VerificationError(lastSeq + 1, "SEQUENCE_GAP",
                            "Falta el registro de la secuencia " + (lastSeq + 1)
                                    + " (hueco hasta " + (e.sequenceId() - 1) + ")"));
                }
                String problem = entryProblem(e, contiguous ? expectedPrev : null);
                if (problem != null) {
                    errors.add(new VerificationError(e.sequenceId(), "HASH_MISMATCH", problem));
                }
                lastSeq = e.sequenceId();
                expectedPrev = e.currentHash();
                after = e.sequenceId();
            }
        }
        return verified;
    }

    /** Devuelve la descripcion del problema del registro o null si es consistente. */
    static String entryProblem(AuditEntry e, String expectedPrev) {
        if (!HashChainService.payloadSha256(e.payload()).equals(e.payloadSha256())) {
            return "El payload no coincide con su huella SHA-256 (alteracion)";
        }
        if (expectedPrev != null && !expectedPrev.equals(e.previousHash())) {
            return "El previous_hash no coincide con el hash del registro anterior";
        }
        if (!HashChainService.recompute(e).equals(e.currentHash())) {
            return "El hash registrado no coincide con el recalculo SHA-256.";
        }
        return null;
    }

    private void checkHead(UUID tenantId, Head head, long max, List<VerificationError> errors) {
        Optional<AuditEntry> last = repo.lastEntry(tenantId);
        String lastHash = last.map(AuditEntry::currentHash).orElse(HashChainService.GENESIS);
        if (head.lastSequenceId() != max || !head.lastHash().equals(lastHash)) {
            errors.add(new VerificationError(max, "HASH_MISMATCH",
                    "La cabeza de cadena no coincide con el ultimo registro"));
        }
    }

    private void verifyAnchors(UUID tenantId, List<WormAnchor> all, List<WormAnchor> selected,
                               List<VerificationError> errors) {
        TenantId tenant = new TenantId(tenantId.toString());
        for (WormAnchor a : selected) {
            try {
                JsonNode doc = readAnchor(tenant, a.fileUri());
                JsonNode manifest = doc.path("manifest");
                byte[] manifestBytes = CanonicalJson.bytes(manifest);
                String hash = HashChainService.sha256Hex(manifestBytes);
                if (!hash.equals(a.manifestHash()) || !hash.equals(doc.path("manifestHash").asText())) {
                    errors.add(anchorError(a, "El manifiesto WORM no coincide con el hash registrado"));
                    continue;
                }
                byte[] sig = Base64.getDecoder().decode(doc.path("signature").path("value").asText());
                String keyId = doc.path("signature").path("keyId").asText(signingKeyId);
                if (!keyId.equals(signingKeyId) || !keys.verify(tenant, manifestBytes, sig, keyId)) {
                    errors.add(anchorError(a, "La firma del ancla WORM es invalida"));
                    continue;
                }
                compareManifest(tenantId, a, manifest, errors);
                previousLink(all, a, manifest).ifPresent(msg -> errors.add(anchorError(a, msg)));
            } catch (RuntimeException | IOException ex) {
                errors.add(anchorError(a, "No se pudo verificar el ancla WORM: " + ex.getClass().getSimpleName()));
            }
        }
    }

    private static VerificationError anchorError(WormAnchor a, String description) {
        return new VerificationError(a.startSequenceId(), "WORM_DISCREPANCY", description);
    }

    private void compareManifest(UUID tenantId, WormAnchor a, JsonNode manifest, List<VerificationError> errors) {
        List<Long> seqs = new ArrayList<>();
        manifest.path("entries").forEach(n -> seqs.add(n.path("sequenceId").asLong()));
        Map<Long, String> db = repo.hashesBySequence(tenantId, seqs);
        for (JsonNode n : manifest.path("entries")) {
            long seq = n.path("sequenceId").asLong();
            if (!n.path("currentHash").asText().equals(db.get(seq))) {
                errors.add(new VerificationError(seq, "WORM_DISCREPANCY",
                        "El registro difiere de lo anclado en WORM (ancla " + a.anchorId() + ")"));
            }
        }
    }

    private static Optional<String> previousLink(List<WormAnchor> all, WormAnchor a, JsonNode manifest) {
        WormAnchor previous = null;
        for (WormAnchor x : all) {
            if (x.startSequenceId() < a.startSequenceId()) {
                previous = x;
            }
        }
        String linked = manifest.path("previousManifestHash").asText(null);
        String expected = previous == null ? null : previous.manifestHash();
        if (Objects.equals(linked, expected)) {
            return Optional.empty();
        }
        return Optional.of("El enlace al manifiesto previo no coincide (ancla faltante o alterada)");
    }

    private JsonNode readAnchor(TenantId tenant, String path) throws IOException {
        try (InputStream in = store.get(tenant, path); GZIPInputStream gz = new GZIPInputStream(in)) {
            return CanonicalJson.parse(new String(gz.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
