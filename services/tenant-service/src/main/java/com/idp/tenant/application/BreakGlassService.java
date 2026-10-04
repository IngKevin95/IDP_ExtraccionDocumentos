package com.idp.tenant.application;

import com.idp.tenant.domain.Exceptions.BadRequestException;
import com.idp.tenant.domain.Exceptions.ConflictException;
import com.idp.tenant.domain.Exceptions.ForbiddenException;
import com.idp.tenant.domain.Exceptions.NotFoundException;
import com.idp.tenant.domain.RoleAssignment;
import com.idp.tenant.domain.Tenant;
import com.idp.tenant.domain.TenantRole;
import com.idp.tenant.domain.TenantStatus;
import com.idp.tenant.infrastructure.persistence.RoleAssignmentRepository;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import com.idp.tenant.security.CachingRoleAssignmentVerifier;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Acceso excepcional (break-glass): justificacion, TTL, aprobador distinto y evento visible al tenant. */
@Service
public class BreakGlassService {
    private final RoleAssignmentRepository roles;
    private final TenantRepository tenants;
    private final CachingRoleAssignmentVerifier verifier;
    private final TenantEvents events;
    private final Clock clock;
    private final long maxTtlMinutes;

    public BreakGlassService(RoleAssignmentRepository roles, TenantRepository tenants,
                             CachingRoleAssignmentVerifier verifier, TenantEvents events, Clock clock,
                             @Value("${idp.tenant.breakglass.max-ttl-minutes:480}") long maxTtlMinutes) {
        this.roles = roles;
        this.tenants = tenants;
        this.verifier = verifier;
        this.events = events;
        this.clock = clock;
        this.maxTtlMinutes = maxTtlMinutes;
    }

    @Transactional
    public RoleAssignment grant(UUID tenantId, String userId, String approvedBy, long ttlMinutes,
                                String justification, String requester) {
        if (justification == null || justification.isBlank()) {
            throw new BadRequestException("Se requiere justificacion");
        }
        if (ttlMinutes < 1 || ttlMinutes > maxTtlMinutes) {
            throw new BadRequestException("TTL fuera de rango (1.." + maxTtlMinutes + " minutos)");
        }
        if (approvedBy == null || approvedBy.isBlank() || approvedBy.equals(requester) || approvedBy.equals(userId)) {
            throw new ForbiddenException("El aprobador debe ser un administrador distinto del solicitante");
        }
        Tenant tenant = tenants.find(tenantId).orElseThrow(() -> new NotFoundException("Tenant no encontrado"));
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new ConflictException("El tenant no esta activo");
        }
        Instant now = Instant.now(clock);
        Instant expiresAt = now.plus(Duration.ofMinutes(ttlMinutes));
        RoleAssignment ra = new RoleAssignment(UUID.randomUUID(), tenantId, userId, TenantRole.BREAK_GLASS.name(),
                requester, approvedBy, expiresAt, now, null, null, null, justification);
        roles.insert(ra);
        events.breakglassOtorgado(tenantId, userId, approvedBy, expiresAt);
        verifier.invalidate(tenantId, userId);
        return ra;
    }

    /** Cierra las sesiones break-glass vencidas (borrado logico) y emite breakglass.expirado. */
    @Transactional
    public int expireDue() {
        Instant now = Instant.now(clock);
        List<RoleAssignment> due = roles.findExpiredActiveByRole(TenantRole.BREAK_GLASS.name(), now);
        for (RoleAssignment r : due) {
            roles.softDelete(r.id(), "system", "EXPIRED", now);
            events.breakglassExpirado(r.tenantId(), r.userId());
            verifier.invalidate(r.tenantId(), r.userId());
        }
        return due.size();
    }
}
