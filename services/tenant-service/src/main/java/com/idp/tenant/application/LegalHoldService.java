package com.idp.tenant.application;

import com.idp.tenant.domain.Exceptions.BadRequestException;
import com.idp.tenant.domain.Exceptions.ConflictException;
import com.idp.tenant.domain.Exceptions.NotFoundException;
import com.idp.tenant.domain.LegalHold;
import com.idp.tenant.domain.LegalHoldReason;
import com.idp.tenant.domain.Tenant;
import com.idp.tenant.domain.TenantStatus;
import com.idp.tenant.infrastructure.persistence.LegalHoldRepository;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LegalHoldService {
    private final TenantRepository tenants;
    private final LegalHoldRepository holds;
    private final TenantEvents events;
    private final Clock clock;

    public LegalHoldService(TenantRepository tenants, LegalHoldRepository holds, TenantEvents events, Clock clock) {
        this.tenants = tenants;
        this.holds = holds;
        this.events = events;
        this.clock = clock;
    }

    /** Aplica retencion legal; idempotente si ya hay una activa. Devuelve el id de la retencion activa. */
    @Transactional
    public UUID apply(UUID tenantId, String reasonCode, String actor) {
        Tenant tenant = tenants.findForUpdate(tenantId).orElseThrow(() -> new NotFoundException("Tenant no encontrado"));
        if (tenant.status() == TenantStatus.DELETED) {
            throw new ConflictException("El tenant ya fue eliminado");
        }
        LegalHoldReason reason;
        try {
            reason = reasonCode == null ? LegalHoldReason.REGULATORY_REQUIREMENT : LegalHoldReason.valueOf(reasonCode);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("reasonCode invalido");
        }
        List<LegalHold> active = holds.findActive(tenantId);
        if (!active.isEmpty()) {
            return active.get(0).id();
        }
        LegalHold hold = new LegalHold(UUID.randomUUID(), tenantId, reason.name(), actor, Instant.now(clock), null, null);
        holds.insert(hold);
        events.legalHoldAplicado(tenantId, hold.id(), reason.name(), actor);
        return hold.id();
    }

    @Transactional
    public void release(UUID tenantId, String actor) {
        tenants.find(tenantId).orElseThrow(() -> new NotFoundException("Tenant no encontrado"));
        Instant now = Instant.now(clock);
        for (LegalHold h : holds.findActive(tenantId)) {
            holds.release(h.id(), actor, now);
            events.legalHoldLiberado(tenantId, h.id(), actor);
        }
    }
}
