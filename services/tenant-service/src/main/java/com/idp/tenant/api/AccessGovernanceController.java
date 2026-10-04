package com.idp.tenant.api;

import com.idp.tenant.api.ApiModels.BreakGlassRequest;
import com.idp.tenant.api.ApiModels.CertificationRequest;
import com.idp.tenant.api.ApiModels.CertificationResponse;
import com.idp.tenant.api.ApiModels.RoleAssignmentResponse;
import com.idp.tenant.api.ApiModels.VerificationResponse;
import com.idp.tenant.application.AccessCertificationService;
import com.idp.tenant.application.BreakGlassService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** Break-glass y certificacion trimestral de accesos (SEC-012); solo rol de plataforma. */
@RestController
@RequestMapping("/v1/admin/tenants/{id}")
public class AccessGovernanceController {
    private final BreakGlassService breakGlass;
    private final AccessCertificationService certifications;

    public AccessGovernanceController(BreakGlassService breakGlass, AccessCertificationService certifications) {
        this.breakGlass = breakGlass;
        this.certifications = certifications;
    }

    @PostMapping("/break-glass")
    @ResponseStatus(HttpStatus.CREATED)
    public RoleAssignmentResponse grant(@PathVariable("id") UUID id, @Valid @RequestBody BreakGlassRequest req,
                                        Authentication auth) {
        return RoleAssignmentResponse.of(breakGlass.grant(id, req.userId(), req.approvedBy(), req.ttlMinutes(),
                req.justification(), auth.getName()));
    }

    @PostMapping("/access-certifications")
    @ResponseStatus(HttpStatus.CREATED)
    public CertificationResponse certify(@PathVariable("id") UUID id, @Valid @RequestBody CertificationRequest req,
                                         Authentication auth) {
        return CertificationResponse.of(certifications.generate(id, req.year(), req.quarter(), auth.getName()));
    }

    @GetMapping("/access-certifications/{certId}")
    public CertificationResponse getCertification(@PathVariable("id") UUID id, @PathVariable("certId") UUID certId) {
        return CertificationResponse.of(certifications.get(id, certId));
    }

    @GetMapping("/access-certifications/{certId}/verification")
    public VerificationResponse verify(@PathVariable("id") UUID id, @PathVariable("certId") UUID certId) {
        return new VerificationResponse(certifications.verify(id, certId));
    }
}
