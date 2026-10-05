package com.idp.document.web;

import com.idp.document.domain.Classification;
import com.idp.document.domain.DocumentStatus;
import com.idp.security.Roles;
import com.idp.document.domain.Typology;
import com.idp.document.service.Caller;
import com.idp.document.service.CallerResolver;
import com.idp.document.service.DocumentIngestionService;
import com.idp.document.service.DocumentIngestionService.IngestCommand;
import com.idp.document.service.DocumentIngestionService.IngestResult;
import com.idp.document.service.DocumentLifecycleService;
import com.idp.document.service.DocumentQueryService;
import com.idp.document.service.DocumentQueryService.Content;
import com.idp.document.service.DocumentQueryService.DownloadLink;
import com.idp.document.service.Exceptions.InvalidMetadataException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** API de documentos (contracts/openapi/document-service.yaml). El tenant sale siempre del JWT. */
@RestController
@RequestMapping("/v1/documents")
public class DocumentController {

    private static final Pattern RADICADO = Pattern.compile("\\d{23}");
    private static final String[] ANY = {Roles.OPERADOR, Roles.DATA_STEWARD, Roles.TENANT_ADMIN};

    private final CallerResolver callers;
    private final DocumentIngestionService ingestion;
    private final DocumentQueryService queries;
    private final DocumentLifecycleService lifecycle;

    public DocumentController(CallerResolver callers, DocumentIngestionService ingestion,
                              DocumentQueryService queries, DocumentLifecycleService lifecycle) {
        this.callers = callers;
        this.ingestion = ingestion;
        this.queries = queries;
        this.lifecycle = lifecycle;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> ingest(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("file") MultipartFile file,
            @RequestParam("typology") String typology,
            @RequestParam("radicado") String radicado,
            @RequestParam(value = "version", defaultValue = "1") int version,
            @RequestParam(value = "classification", defaultValue = "CONFIDENCIAL") String classification,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey) {
        Caller caller = callers.require(jwt, Roles.OPERADOR, Roles.TENANT_ADMIN);
        Typology t = parse(Typology.class, typology, "typology");
        Classification c = parse(Classification.class, classification, "classification");
        if (!RADICADO.matcher(radicado).matches()) {
            throw new InvalidMetadataException("El radicado debe tener 23 digitos");
        }
        if (version < 1) {
            throw new InvalidMetadataException("La version debe ser >= 1");
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > 128)) {
            throw new InvalidMetadataException("Idempotency-Key invalida");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        IngestResult r = ingestion.ingest(new IngestCommand(caller, file.getOriginalFilename(), bytes, t, radicado,
                version, c, idempotencyKey));
        return ResponseEntity.status(r.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(DocumentResponse.of(r.document()));
    }

    @GetMapping
    public ListResponse list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Caller caller = callers.require(jwt, ANY);
        DocumentStatus s = status == null ? null : parse(DocumentStatus.class, status, "status");
        var page = queries.list(caller, s, limit, offset);
        return new ListResponse(page.data().stream().map(DocumentResponse::of).toList(), page.limit(),
                page.offset(), page.total());
    }

    @GetMapping("/{documentId}")
    public DocumentResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable("documentId") UUID documentId) {
        return DocumentResponse.of(queries.get(callers.require(jwt, ANY), documentId));
    }

    @GetMapping("/{documentId}/download")
    public DownloadResponse download(@AuthenticationPrincipal Jwt jwt, @PathVariable("documentId") UUID documentId) {
        DownloadLink link = queries.downloadLink(callers.require(jwt, ANY), documentId);
        return new DownloadResponse(link.url(), link.expiresAt());
    }

    /** No esta en el contrato OpenAPI: destino del enlace firmado de /download (el binario va cifrado). */
    @GetMapping("/{documentId}/content")
    public ResponseEntity<byte[]> content(@AuthenticationPrincipal Jwt jwt, @PathVariable("documentId") UUID documentId,
            @RequestParam("exp") long exp, @RequestParam("sig") String sig) {
        Content c = queries.content(callers.require(jwt, ANY), documentId, exp, sig);
        return ResponseEntity.ok()
                .header("Content-Disposition", "attachment")
                .header("X-Content-Type-Options", "nosniff")
                .contentType(MediaType.parseMediaType(c.mimeType()))
                .body(c.bytes());
    }

    @PostMapping("/{documentId}/approve-confidential")
    public DocumentResponse approveConfidential(@AuthenticationPrincipal Jwt jwt, @PathVariable("documentId") UUID documentId) {
        Caller caller = callers.require(jwt, Roles.DATA_STEWARD);
        return DocumentResponse.of(lifecycle.approveConfidential(caller, documentId));
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("documentId") UUID documentId) {
        lifecycle.purge(callers.require(jwt, Roles.TENANT_ADMIN), documentId);
        return ResponseEntity.noContent().build();
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            throw new InvalidMetadataException("Valor invalido para " + field);
        }
    }

    public record ListResponse(List<DocumentResponse> data, int limit, int offset, long total) {
    }

    public record DownloadResponse(String downloadUrl, Instant expiresAt) {
    }
}
