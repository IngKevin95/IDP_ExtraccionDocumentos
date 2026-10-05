package com.idp.tenant.application;

import com.idp.tenant.domain.Exceptions.BadRequestException;
import com.idp.tenant.domain.Exceptions.ConflictException;
import com.idp.tenant.domain.Exceptions.ForbiddenException;
import com.idp.tenant.domain.Exceptions.NotFoundException;
import com.idp.tenant.domain.RoleAssignment;
import com.idp.tenant.domain.TenantRole;
import com.idp.tenant.infrastructure.persistence.RoleAssignmentRepository;
import com.idp.tenant.security.CachingRoleAssignmentVerifier;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Usuarios y roles del tenant sobre role_assignment (borrado logico). El tenant llega siempre del token. */
@Service
public class UserAccessService {
    private final RoleAssignmentRepository roles;
    private final CachingRoleAssignmentVerifier verifier;
    private final TenantEvents events;
    private final Clock clock;
    private final ApprovalService approvals;

    /** SoD (A4): roles de control que nadie puede autoasignarse y que exigen un aprobador distinto. */
    public static final Set<TenantRole> SENSITIVE = EnumSet.of(TenantRole.DATA_STEWARD, TenantRole.AUDITOR,
            TenantRole.OFICIAL_SEGURIDAD, TenantRole.COMPLIANCE, TenantRole.RIESGO_MODELO);

    /** assignment es nulo mientras la asignacion espera la aprobacion de un segundo administrador. */
    public record AssignOutcome(RoleAssignment assignment, String requestedBy) {
        public boolean pending() {
            return assignment == null;
        }
    }

    public UserAccessService(RoleAssignmentRepository roles, CachingRoleAssignmentVerifier verifier,
                             TenantEvents events, Clock clock, ApprovalService approvals) {
        this.approvals = approvals;
        this.roles = roles;
        this.verifier = verifier;
        this.events = events;
        this.clock = clock;
    }

    public List<RoleAssignment> list(UUID tenantId) {
        return roles.findActiveByTenant(tenantId, Instant.now(clock));
    }

    @Transactional
    public AssignOutcome assign(UUID tenantId, String userId, TenantRole role, Instant expiresAt, String actor) {
        if (role == TenantRole.BREAK_GLASS) {
            throw new BadRequestException("BREAK_GLASS solo se otorga por el flujo de plataforma");
        }
        Instant now = Instant.now(clock);
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw new BadRequestException("expiresAt debe ser futuro");
        }
        boolean exists = roles.findActiveByUser(tenantId, userId, now).stream()
                .anyMatch(r -> r.role().equals(role.name()));
        if (exists) {
            throw new ConflictException("El usuario ya tiene el rol");
        }
        String grantedBy = actor;
        String approvedBy = null;
        if (SENSITIVE.contains(role)) {
            if (userId.equals(actor)) {
                throw new ForbiddenException("Segregacion de funciones: no puede asignarse a si mismo el rol " + role);
            }
            var result = approvals.requestOrApprove(tenantId, ApprovalService.ASSIGN_SENSITIVE_ROLE,
                    userId + "|" + role.name(), actor);
            if (result.outcome() != ApprovalService.Outcome.APPROVED) {
                return new AssignOutcome(null, result.requestedBy());
            }
            grantedBy = result.requestedBy();
            approvedBy = actor;
        }
        RoleAssignment ra = new RoleAssignment(UUID.randomUUID(), tenantId, userId, role.name(), grantedBy,
                approvedBy, expiresAt, now, null, null, null, null);
        roles.insert(ra);
        if (approvedBy != null) {
            events.rolSensibleOtorgado(tenantId, userId, role.name(), grantedBy, approvedBy);
        }
        verifier.invalidate(tenantId, userId);
        return new AssignOutcome(ra, grantedBy);
    }

    /** Revoca todos los roles activos del usuario y emite acceso.revocado. */
    @Transactional
    public void revoke(UUID tenantId, String userId, String actor) {
        if (userId.equals(actor)) {
            throw new ConflictException("No se puede revocar el propio acceso");
        }
        Instant now = Instant.now(clock);
        List<RoleAssignment> active = roles.findActiveByUser(tenantId, userId, now);
        if (active.isEmpty()) {
            throw new NotFoundException("Usuario sin roles activos");
        }
        for (RoleAssignment r : active) {
            roles.softDelete(r.id(), actor, "REVOKED", now);
        }
        events.accesoRevocado(tenantId, userId);
        verifier.invalidate(tenantId, userId);
    }
}
