package com.idp.tenant.application;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.kms.KeyService;
import com.idp.tenant.TenantId;
import com.idp.tenant.domain.AccessCertification;
import com.idp.tenant.domain.Exceptions.BadRequestException;
import com.idp.tenant.domain.Exceptions.NotFoundException;
import com.idp.tenant.domain.RoleAssignment;
import com.idp.tenant.infrastructure.persistence.CertificationRepository;
import com.idp.tenant.infrastructure.persistence.JsonSupport;
import com.idp.tenant.infrastructure.persistence.RoleAssignmentRepository;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Reporte trimestral de certificacion de accesos (SEC-012): asignaciones de rol vigentes en algun momento
 * del trimestre, firmadas con el puerto KeyService. El reporte no contiene datos documentales.
 */
@Service
public class AccessCertificationService {
    private final TenantRepository tenants;
    private final RoleAssignmentRepository roles;
    private final CertificationRepository certifications;
    private final KeyService keyService;
    private final Clock clock;
    private final String signingKeyId;

    public AccessCertificationService(TenantRepository tenants, RoleAssignmentRepository roles,
                                      CertificationRepository certifications, KeyService keyService, Clock clock,
                                      @Value("${idp.tenant.certification.signing-key-id:access-certification}") String signingKeyId) {
        this.tenants = tenants;
        this.roles = roles;
        this.certifications = certifications;
        this.keyService = keyService;
        this.clock = clock;
        this.signingKeyId = signingKeyId;
    }

    public AccessCertification generate(UUID tenantId, int year, int quarter, String actor) {
        if (quarter < 1 || quarter > 4 || year < 2000 || year > 2100) {
            throw new BadRequestException("Periodo invalido");
        }
        tenants.find(tenantId).orElseThrow(() -> new NotFoundException("Tenant no encontrado"));
        Instant from = LocalDate.of(year, (quarter - 1) * 3 + 1, 1).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant to = LocalDate.of(year, (quarter - 1) * 3 + 1, 1).plusMonths(3).atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant now = Instant.now(clock);
        if (from.isAfter(now)) {
            throw new BadRequestException("El periodo aun no inicia");
        }

        List<RoleAssignment> inPeriod = roles.findAllByTenant(tenantId).stream()
                .filter(r -> r.createdAt().isBefore(to))
                .filter(r -> {
                    Instant end = earliest(r.deletedAt(), r.expiresAt());
                    return end == null || !end.isBefore(from);
                })
                .sorted(Comparator.comparing(RoleAssignment::userId).thenComparing(RoleAssignment::role)
                        .thenComparing(RoleAssignment::createdAt))
                .toList();

        ObjectNode report = JsonSupport.MAPPER.createObjectNode();
        report.put("tenantId", tenantId.toString());
        ObjectNode period = report.putObject("period");
        period.put("year", year).put("quarter", quarter).put("from", from.toString()).put("to", to.toString());
        report.put("generatedAt", now.toString()).put("generatedBy", actor);
        ArrayNode items = report.putArray("assignments");
        for (RoleAssignment r : inPeriod) {
            ObjectNode n = items.addObject();
            n.put("userId", r.userId()).put("role", r.role()).put("grantedBy", r.grantedBy());
            n.put("approvedBy", r.approvedBy());
            n.put("grantedAt", r.createdAt().toString());
            n.put("expiresAt", r.expiresAt() == null ? null : r.expiresAt().toString());
            n.put("revokedAt", r.deletedAt() == null ? null : r.deletedAt().toString());
            n.put("revokeReason", r.deleteReason());
        }
        String reportJson = report.toString();
        byte[] bytes = reportJson.getBytes(StandardCharsets.UTF_8);
        byte[] signature = keyService.sign(new TenantId(tenantId.toString()), bytes, signingKeyId).getData();

        AccessCertification cert = new AccessCertification(UUID.randomUUID(), tenantId, year, quarter, actor, now,
                reportJson, sha256(bytes), Base64.getEncoder().encodeToString(signature), signingKeyId);
        certifications.insert(cert);
        return cert;
    }

    public AccessCertification get(UUID tenantId, UUID id) {
        return certifications.find(tenantId, id)
                .orElseThrow(() -> new NotFoundException("Certificacion no encontrada"));
    }

    public boolean verify(UUID tenantId, UUID id) {
        AccessCertification c = get(tenantId, id);
        byte[] bytes = c.reportJson().getBytes(StandardCharsets.UTF_8);
        return sha256(bytes).equals(c.reportSha256()) && keyService.verify(new TenantId(tenantId.toString()), bytes,
                Base64.getDecoder().decode(c.signature()), c.keyId());
    }

    private static Instant earliest(Instant a, Instant b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.isBefore(b) ? a : b;
    }

    private static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
