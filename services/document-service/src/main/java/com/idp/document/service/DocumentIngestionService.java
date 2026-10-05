package com.idp.document.service;

import com.idp.document.config.DocumentProperties;
import com.idp.document.config.RendererProperties;
import com.idp.document.domain.Classification;
import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.DocumentStatus;
import com.idp.document.domain.PageArtifact;
import com.idp.document.domain.Typology;
import com.idp.document.infra.ArtifactVault;
import com.idp.document.infra.DocumentRepository;
import com.idp.document.infra.DomainEvents;
import com.idp.document.infra.RendererClient;
import com.idp.document.infra.RendererClient.RejectedException;
import com.idp.document.infra.RendererClient.RenderResult;
import com.idp.document.infra.RendererClient.UnavailableException;
import com.idp.document.service.Exceptions.ConflictException;
import com.idp.document.service.Exceptions.FileTooLargeException;
import com.idp.document.service.Exceptions.InvalidFileFormatException;
import io.micrometer.core.instrument.MeterRegistry;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Carga idempotente (SEC-029, RN-01), guardado cifrado en el bucket del tenant y orquestacion sincrona del
 * renderer. Los eventos salen por outbox en la misma transaccion que el cambio de estado.
 */
@Service
public class DocumentIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentIngestionService.class);

    public record IngestCommand(Caller caller, String filename, byte[] content, Typology typology, String radicado,
                                int version, Classification classification, String idempotencyKey) {
    }

    public record IngestResult(DocumentRecord document, boolean created) {
    }

    private final FileValidator validator;
    private final DocumentRepository repo;
    private final ArtifactVault vault;
    private final RendererClient renderer;
    private final DocumentStateMachine states;
    private final DomainEvents events;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;
    private final Clock clock;
    private final DocumentProperties props;
    private final RendererProperties rendererProps;

    public DocumentIngestionService(FileValidator validator, DocumentRepository repo, ArtifactVault vault,
                                    RendererClient renderer, DocumentStateMachine states, DomainEvents events,
                                    TransactionTemplate tx, MeterRegistry meters, Clock clock,
                                    DocumentProperties props, RendererProperties rendererProps) {
        this.validator = validator;
        this.repo = repo;
        this.vault = vault;
        this.renderer = renderer;
        this.states = states;
        this.events = events;
        this.tx = tx;
        this.meters = meters;
        this.clock = clock;
        this.props = props;
        this.rendererProps = rendererProps;
    }

    public IngestResult ingest(IngestCommand c) {
        String tenant = c.caller().tenantId();
        String mime;
        try {
            mime = validator.validate(c.content(), props.maxFileBytes());
        } catch (InvalidFileFormatException e) {
            countRejected("INVALID_MAGIC_BYTES");
            throw e;
        } catch (FileTooLargeException e) {
            countRejected("FILE_SIZE_EXCEEDED");
            throw e;
        }
        String hash = sha256(c.content());

        Optional<DocumentRecord> existing = findExisting(c, hash);
        if (existing.isPresent()) {
            return new IngestResult(existing.get(), false);
        }

        UUID id = UUID.randomUUID();
        String key = ArtifactVault.originalKey(id);
        vault.put(tenant, id, key, mime, c.content());
        OffsetDateTime now = OffsetDateTime.now(clock);
        DocumentRecord doc = new DocumentRecord(id, tenant, hash, c.typology().name(), c.radicado(), c.version(),
                DocumentStatus.RECIBIDO, c.classification(), key, mime, (long) c.content().length,
                c.caller().userId(), c.idempotencyKey(), now, now, null);
        try {
            tx.executeWithoutResult(s -> {
                repo.insert(doc);
                events.recibido(doc);
            });
        } catch (DuplicateKeyException race) {
            vault.delete(tenant, key);
            Optional<DocumentRecord> winner = findExisting(c, hash);
            if (winner.isPresent()) {
                return new IngestResult(winner.get(), false);
            }
            throw race;
        }
        return new IngestResult(process(doc, c.filename(), mime, c.content()), true);
    }

    private Optional<DocumentRecord> findExisting(IngestCommand c, String hash) {
        String tenant = c.caller().tenantId();
        if (c.idempotencyKey() != null) {
            Optional<DocumentRecord> byKey = repo.findByIdempotencyKey(tenant, c.idempotencyKey());
            if (byKey.isPresent()) {
                if (!hash.equals(byKey.get().hashSha256())) {
                    throw new ConflictException("DOC_IDEMPOTENCY_KEY_REUSED",
                            "La Idempotency-Key ya se uso con otro contenido");
                }
                return byKey;
            }
        }
        Optional<DocumentRecord> byHash = repo.findByHash(tenant, hash);
        if (byHash.isPresent()) {
            return byHash;
        }
        if (repo.findByBusinessKey(tenant, c.typology().name(), c.radicado(), c.version()).isPresent()) {
            throw new ConflictException("DOC_DUPLICATE_BUSINESS_KEY",
                    "Ya existe un documento con la misma tipologia, radicado y version");
        }
        return Optional.empty();
    }

    private DocumentRecord process(DocumentRecord doc, String filename, String mime, byte[] content) {
        RenderResult result;
        try {
            result = renderWithRetry(filename, mime, content);
        } catch (RejectedException e) {
            LOG.warn("Renderer rechazo el documento {}: {}", doc.id(), e.code());
            return finish(doc, DocumentStatus.RECHAZADO, reasonFor(e));
        } catch (UnavailableException e) {
            LOG.error("Renderer no disponible para el documento {}", doc.id());
            return finish(doc, DocumentStatus.FALLIDO, "RENDERER_FAILED");
        }
        List<PageArtifact> artifacts = new ArrayList<>();
        try {
            for (RendererClient.Page p : result.pages()) {
                String k = ArtifactVault.pageKey(doc.id(), p.number());
                vault.put(doc.tenantId(), doc.id(), k, "image/png", p.png());
                artifacts.add(new PageArtifact(UUID.randomUUID(), doc.id(), PageArtifact.PAGE_PNG, p.number(), k));
            }
            String tk = ArtifactVault.textLayerKey(doc.id());
            vault.put(doc.tenantId(), doc.id(), tk, "application/json", result.textLayer());
            artifacts.add(new PageArtifact(UUID.randomUUID(), doc.id(), PageArtifact.TEXT_LAYER, 0, tk));
        } catch (RuntimeException e) {
            LOG.error("No se pudieron guardar los artefactos del documento {}: {}", doc.id(),
                    e.getClass().getSimpleName());
            artifacts.forEach(a -> deleteQuietly(doc.tenantId(), a.objectStoreKey()));
            return finish(doc, DocumentStatus.FALLIDO, "RENDERER_FAILED");
        }
        return tx.execute(s -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            artifacts.forEach(a -> repo.insertArtifact(a, now));
            DocumentRecord rendered = states.transition(doc, DocumentStatus.RENDERIZADO);
            events.renderizado(rendered, artifacts.stream()
                    .filter(a -> PageArtifact.PAGE_PNG.equals(a.kind())).map(PageArtifact::objectStoreKey).toList());
            DocumentRecord extracting = states.transition(rendered, DocumentStatus.EN_EXTRACCION);
            events.extraccionSolicitada(extracting);
            return extracting;
        });
    }

    private DocumentRecord finish(DocumentRecord doc, DocumentStatus terminal, String reasonCode) {
        countRejected(reasonCode);
        return tx.execute(s -> {
            DocumentRecord done = states.transition(doc, terminal);
            events.rechazado(done, reasonCode);
            return done;
        });
    }

    private RenderResult renderWithRetry(String filename, String mime, byte[] content) {
        int attempts = Math.max(1, rendererProps.maxAttempts());
        long backoff = rendererProps.initialBackoff().toMillis();
        UnavailableException last = null;
        for (int i = 0; i < attempts; i++) {
            try {
                return renderer.render(filename, mime, content);
            } catch (UnavailableException e) {
                last = e;
                if (i < attempts - 1) {
                    try {
                        Thread.sleep(backoff << i);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw e;
                    }
                }
            }
        }
        throw last;
    }

    static String reasonFor(RejectedException e) {
        return switch (e.code()) {
            case "ERR_MALWARE_DETECTED" -> "MALWARE_DETECTED";
            case "ERR_UNSUPPORTED_FORMAT", "ERR_VALIDATION" -> "INVALID_MAGIC_BYTES";
            default -> switch (e.status()) {
                case 413 -> "FILE_SIZE_EXCEEDED";
                case 400 -> "INVALID_MAGIC_BYTES";
                default -> "ANTIVIRUS_POSITIVE";
            };
        };
    }

    private void deleteQuietly(String tenant, String key) {
        try {
            vault.delete(tenant, key);
        } catch (RuntimeException ignored) {
            // limpieza best-effort
        }
    }

    private void countRejected(String reason) {
        meters.counter("idp_document_rejected_total", "reason", reason).increment();
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
