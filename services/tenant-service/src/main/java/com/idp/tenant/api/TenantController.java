package com.idp.tenant.api;

import com.idp.tenant.api.ApiModels.ConsumptionRequest;
import com.idp.tenant.api.ApiModels.LegalHoldRequest;
import com.idp.tenant.api.ApiModels.LegalHoldResponse;
import com.idp.tenant.api.ApiModels.TenantConfigUpdateRequest;
import com.idp.tenant.api.ApiModels.TenantCreateRequest;
import com.idp.tenant.api.ApiModels.TenantResponse;
import com.idp.tenant.application.ApprovalService;
import com.idp.tenant.application.LegalHoldService;
import com.idp.tenant.application.OffboardingService;
import com.idp.tenant.application.QuotaService;
import com.idp.tenant.application.TenantAdminService;
import com.idp.tenant.application.TenantProvisioningService;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Administracion de tenants: solo rol de plataforma (PLATFORM_ADMIN), reforzado en SecurityConfig. */
@RestController
@RequestMapping("/v1/admin/tenants")
public class TenantController {
    private final TenantProvisioningService provisioning;
    private final TenantAdminService admin;
    private final OffboardingService offboarding;
    private final LegalHoldService legalHolds;
    private final QuotaService quotas;
    private final ApprovalService approvals;

    public TenantController(TenantProvisioningService provisioning, TenantAdminService admin,
                            OffboardingService offboarding, LegalHoldService legalHolds, QuotaService quotas,
                            ApprovalService approvals) {
        this.approvals = approvals;
        this.provisioning = provisioning;
        this.admin = admin;
        this.offboarding = offboarding;
        this.legalHolds = legalHolds;
        this.quotas = quotas;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TenantResponse create(@Valid @RequestBody TenantCreateRequest req) {
        return TenantResponse.of(provisioning.create(req.name().trim(), req.planId()));
    }

    @GetMapping
    public List<TenantResponse> list() {
        return admin.list().stream().map(TenantResponse::of).toList();
    }

    @GetMapping("/{id}")
    public TenantResponse get(@PathVariable("id") UUID id) {
        return TenantResponse.of(admin.get(id));
    }

    @PutMapping("/{id}/config")
    public TenantResponse updateConfig(@PathVariable("id") UUID id, @Valid @RequestBody TenantConfigUpdateRequest req) {
        return TenantResponse.of(admin.updateConfig(id, req.planId(), req.settings()));
    }

    /**
     * Baja de tenant: exige la aprobacion de un segundo PLATFORM_ADMIN distinto (A3). La primera llamada registra la
     * solicitud (202, el tenant sigue como esta); la de otro administrador la aprueba e inicia la baja.
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public TenantResponse delete(@PathVariable("id") UUID id, Authentication auth) {
        var tenant = offboarding.assertCanInitiate(id);
        if (tenant.status() != com.idp.tenant.domain.TenantStatus.PENDING_DELETION
                && approvals.requestOrApprove(id, ApprovalService.DELETE_TENANT, id.toString(), auth.getName())
                        .outcome() != ApprovalService.Outcome.APPROVED) {
            return TenantResponse.of(tenant);
        }
        return TenantResponse.of(offboarding.initiate(id));
    }

    @PostMapping({"/{id}/legal-hold", "/{id}/legal-holds"})
    public ResponseEntity<LegalHoldResponse> legalHold(@PathVariable("id") UUID id,
                                                      @Valid @RequestBody LegalHoldRequest req,
                                                      Authentication auth) {
        if (req.active()) {
            return ResponseEntity.ok(new LegalHoldResponse(true, legalHolds.apply(id, req.reasonCode(), auth.getName())));
        }
        // Liberar un legal hold exige la aprobacion de un segundo PLATFORM_ADMIN distinto (A3).
        admin.get(id);
        if (approvals.requestOrApprove(id, ApprovalService.RELEASE_LEGAL_HOLD, id.toString(), auth.getName())
                .outcome() != ApprovalService.Outcome.APPROVED) {
            return ResponseEntity.accepted().body(new LegalHoldResponse(true, null));
        }
        legalHolds.release(id, auth.getName());
        return ResponseEntity.ok(new LegalHoldResponse(false, null));
    }

    @PostMapping("/{id}/consumption")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void consumption(@PathVariable("id") UUID id, @Valid @RequestBody ConsumptionRequest req) {
        quotas.record(id, req.metricName(), req.value());
    }
}
