package com.idp.document.service;

import com.idp.document.config.DocumentProperties;
import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.DocumentStatus;
import com.idp.document.infra.ArtifactVault;
import com.idp.document.infra.DocumentRepository;
import com.idp.document.service.Exceptions.DocumentNotFoundException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

/** Consulta con autorizacion por documento (404 estricto, SEC-003) y enlaces de descarga firmados. */
@Service
public class DocumentQueryService {

    public record Page(List<DocumentRecord> data, int limit, int offset, long total) {
    }

    public record DownloadLink(String url, Instant expiresAt) {
    }

    public record Content(byte[] bytes, String mimeType) {
    }

    private final DocumentRepository repo;
    private final ArtifactVault vault;
    private final DocumentProperties props;
    private final Clock clock;

    public DocumentQueryService(DocumentRepository repo, ArtifactVault vault, DocumentProperties props,
                                Clock clock) {
        this.repo = repo;
        this.vault = vault;
        this.props = props;
        this.clock = clock;
    }

    public DocumentRecord get(Caller caller, UUID id) {
        return repo.findById(caller.tenantId(), id).filter(caller::canView).orElseThrow(DocumentNotFoundException::new);
    }

    public Page list(Caller caller, DocumentStatus status, int limit, int offset) {
        int l = Math.min(Math.max(limit, 1), 100);
        int o = Math.max(offset, 0);
        List<DocumentRecord> data = repo.list(caller.tenantId(), status, caller.userId(), caller.privileged(), l, o);
        return new Page(data, l, o, repo.count(caller.tenantId(), status, caller.userId(), caller.privileged()));
    }

    /**
     * Los objetos estan cifrados con sobre, por lo que una URL presignada del bucket no serviria: el enlace
     * apunta al endpoint de contenido de este servicio, con vencimiento y firma HMAC.
     */
    public DownloadLink downloadLink(Caller caller, UUID id) {
        DocumentRecord d = get(caller, id);
        Instant exp = clock.instant().plus(props.downloadTtl());
        String url = props.publicBaseUrl() + "/v1/documents/" + d.id() + "/content?exp=" + exp.getEpochSecond()
                + "&sig=" + sign(d.tenantId(), d.id(), exp.getEpochSecond());
        return new DownloadLink(url, exp);
    }

    public Content content(Caller caller, UUID id, long exp, String sig) {
        DocumentRecord d = get(caller, id);
        if (clock.instant().getEpochSecond() > exp || sig == null
                || !MessageDigest.isEqual(sig.getBytes(StandardCharsets.UTF_8),
                sign(d.tenantId(), d.id(), exp).getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException("Enlace de descarga invalido o vencido");
        }
        return new Content(vault.getOriginal(d.tenantId(), d.id()), d.mimeType());
    }

    private String sign(String tenantId, UUID id, long exp) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(props.downloadSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((tenantId + "|" + id + "|" + exp)
                    .getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
