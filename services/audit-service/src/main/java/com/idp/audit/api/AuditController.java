package com.idp.audit.api;

import com.idp.audit.api.ApiModels.SignatureVerificationResponse;
import com.idp.audit.application.AuditVerificationService;
import com.idp.audit.application.AuditVerificationService.ChainVerificationReport;
import com.idp.audit.application.CanonicalJson;
import com.idp.audit.application.DossierService;
import com.idp.audit.application.DossierService.SignatureCheck;
import com.idp.audit.domain.Exceptions.BadRequestException;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Expediente firmado, verificacion forense y verificacion publica de firma. El tenant se toma del JWT
 * revalidado (claim tenant_id), jamas del path ni del body.
 */
@RestController
@RequestMapping("/v1/audit")
public class AuditController {

    private final DossierService dossiers;
    private final AuditVerificationService verification;

    public AuditController(DossierService dossiers, AuditVerificationService verification) {
        this.dossiers = dossiers;
        this.verification = verification;
    }

    @GetMapping(value = "/dossiers/{documentId}", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> dossier(@PathVariable("documentId") UUID documentId, Authentication auth) {
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON)
                .body(dossiers.export(TenantClaims.tenantId(auth), documentId));
    }

    @GetMapping("/verify")
    public ChainVerificationReport verify(@RequestParam(name = "startSequenceId", required = false) Long start,
                                          @RequestParam(name = "endSequenceId", required = false) Long end,
                                          Authentication auth) {
        if ((start != null && start < 1) || (end != null && end < 1)) {
            throw new BadRequestException("Las secuencias deben ser >= 1");
        }
        return verification.verify(TenantClaims.tenantId(auth), start, end);
    }

    /**
     * Verificacion publica (sin JWT): recibe un expediente exportado y comprueba su firma ed25519 y los hashes
     * de sus eventos. No devuelve datos del tenant, solo el veredicto.
     */
    @PostMapping(value = "/public/verify-signature", consumes = MediaType.APPLICATION_JSON_VALUE)
    public SignatureVerificationResponse verifySignature(@RequestBody String dossierJson) {
        SignatureCheck check;
        try {
            check = dossiers.verifySignature(CanonicalJson.parse(dossierJson));
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Expediente invalido");
        }
        return new SignatureVerificationResponse(check.valid(), check.signatureValid(), check.eventHashesValid());
    }
}
