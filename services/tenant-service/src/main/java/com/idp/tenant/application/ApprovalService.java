package com.idp.tenant.application;

import com.idp.tenant.domain.Exceptions.ConflictException;
import com.idp.tenant.infrastructure.persistence.ApprovalRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Patron solicitud y aprobacion: la primera persona registra la solicitud; una segunda, distinta, la aprueba y solo
 * entonces se ejecuta la accion. La misma persona repitiendo la llamada no aprueba su propia solicitud.
 */
@Service
public class ApprovalService {
    public static final String DELETE_TENANT = "DELETE_TENANT";
    public static final String RELEASE_LEGAL_HOLD = "RELEASE_LEGAL_HOLD";
    public static final String ASSIGN_SENSITIVE_ROLE = "ASSIGN_SENSITIVE_ROLE";
    static final Duration VALIDITY = Duration.ofHours(24);

    public enum Outcome { REQUESTED, APPROVED }

    /** requestedBy es quien abrio la solicitud (el primero); el aprobador es el actor de la llamada. */
    public record Result(Outcome outcome, String requestedBy) {}

    private final ApprovalRepository approvals;
    private final Clock clock;

    public ApprovalService(ApprovalRepository approvals, Clock clock) {
        this.approvals = approvals;
        this.clock = clock;
    }

    @Transactional
    public Result requestOrApprove(UUID tenantId, String action, String target, String actor) {
        Instant now = Instant.now(clock);
        var pending = approvals.findPending(tenantId, action, target, now.minus(VALIDITY));
        if (pending.isEmpty()) {
            approvals.insert(tenantId, action, target, actor, now);
            return new Result(Outcome.REQUESTED, actor);
        }
        if (pending.get().requestedBy().equals(actor)) {
            return new Result(Outcome.REQUESTED, actor);
        }
        if (!approvals.approve(pending.get().id(), actor, now)) {
            throw new ConflictException("La solicitud ya fue resuelta");
        }
        return new Result(Outcome.APPROVED, pending.get().requestedBy());
    }
}
