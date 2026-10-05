package com.idp.audit.api;

import com.idp.audit.api.ApiModels.LegalHoldRequest;
import com.idp.audit.api.ApiModels.LegalHoldResponse;
import com.idp.audit.api.ApiModels.PurgeCheckRequest;
import com.idp.audit.api.ApiModels.PurgeCheckResponse;
import com.idp.audit.application.LegalHoldService;
import com.idp.audit.application.RetentionService;
import com.idp.audit.application.RetentionService.PurgeDecision;
import com.idp.audit.domain.Exceptions.LegalHoldActiveException;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Legal hold y compuerta de retencion (SEC-042). Tenant siempre del JWT revalidado. */
@RestController
@RequestMapping("/v1/audit")
public class LegalHoldController {

    private static final String DEFAULT_REASON_CODE = "REGULATORY_REQUIREMENT";

    private final LegalHoldService holds;
    private final RetentionService retention;

    public LegalHoldController(LegalHoldService holds, RetentionService retention) {
        this.holds = holds;
        this.retention = retention;
    }

    /** Ruta del contrato OpenAPI (/legal-hold); /legal-holds es el alias de la spec. */
    @PostMapping({"/legal-hold", "/legal-holds"})
    public LegalHoldResponse legalHold(@Valid @RequestBody LegalHoldRequest req, Authentication auth) {
        UUID tenantId = TenantClaims.tenantId(auth);
        return switch (req.action()) {
            case APPLY -> LegalHoldResponse.of(holds.apply(tenantId, req.documentId(), req.reason(),
                    req.reasonCode() == null ? DEFAULT_REASON_CODE : req.reasonCode(), auth.getName()));
            case RELEASE -> LegalHoldResponse.of(holds.release(tenantId, req.documentId(), auth.getName()));
        };
    }

    /**
     * Consulta previa a cualquier expurgo o destruccion de KEK: con legal hold activo responde 409 y deja el
     * rechazo encadenado (AC-08).
     */
    @PostMapping("/retention/purge-check")
    public PurgeCheckResponse purgeCheck(@Valid @RequestBody PurgeCheckRequest req, Authentication auth) {
        PurgeDecision decision = retention.checkPurge(TenantClaims.tenantId(auth), req.documentId(),
                auth.getName());
        if (!decision.allowed()) {
            throw new LegalHoldActiveException("La destruccion esta bloqueada por legal hold activo.");
        }
        return new PurgeCheckResponse(true);
    }
}
