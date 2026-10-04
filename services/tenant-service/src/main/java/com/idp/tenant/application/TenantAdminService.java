package com.idp.tenant.application;

import com.idp.tenant.domain.Exceptions.BadRequestException;
import com.idp.tenant.domain.Exceptions.ConflictException;
import com.idp.tenant.domain.Exceptions.NotFoundException;
import com.idp.tenant.domain.Plan;
import com.idp.tenant.domain.Tenant;
import com.idp.tenant.domain.TenantStatus;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantAdminService {
    private final TenantRepository tenants;
    private final QuotaService quotas;
    private final Clock clock;

    public TenantAdminService(TenantRepository tenants, QuotaService quotas, Clock clock) {
        this.tenants = tenants;
        this.quotas = quotas;
        this.clock = clock;
    }

    public List<Tenant> list() {
        return tenants.list();
    }

    public Tenant get(UUID id) {
        return tenants.find(id).orElseThrow(() -> new NotFoundException("Tenant no encontrado"));
    }

    /** Cambia el plan y la configuracion; los limites del plan se aplican a la cuota vigente. */
    @Transactional
    public Tenant updateConfig(UUID id, UUID planId, Map<String, Object> settings) {
        Tenant tenant = get(id);
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new ConflictException("El tenant no esta activo");
        }
        Plan plan = tenants.findPlan(planId).orElseThrow(() -> new BadRequestException("Plan inexistente"));
        tenants.updatePlan(id, planId, Instant.now(clock));
        if (settings != null) {
            tenants.updateSettings(id, settings);
        }
        quotas.applyPlanLimits(id, plan);
        return get(id);
    }
}
