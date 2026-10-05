package com.idp.tenant.application;

import com.idp.tenant.domain.Exceptions.BadRequestException;
import com.idp.tenant.domain.Exceptions.ConflictException;
import com.idp.tenant.domain.Exceptions.NotFoundException;
import com.idp.tenant.domain.MetricName;
import com.idp.tenant.domain.Plan;
import com.idp.tenant.domain.QuotaPeriod;
import com.idp.tenant.domain.Tenant;
import com.idp.tenant.domain.TenantStatus;
import com.idp.tenant.infrastructure.persistence.QuotaRepository;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Cuotas por plan y consumo reportado; alerta al superar el umbral (por defecto 80%). */
@Service
public class QuotaService {
    private final QuotaRepository quotas;
    private final TenantRepository tenants;
    private final TenantEvents events;
    private final Clock clock;
    private final int thresholdPercent;

    public QuotaService(QuotaRepository quotas, TenantRepository tenants, TenantEvents events, Clock clock,
                        @Value("${idp.tenant.quota.alert-threshold-percent:80}") int thresholdPercent) {
        this.quotas = quotas;
        this.tenants = tenants;
        this.events = events;
        this.clock = clock;
        this.thresholdPercent = thresholdPercent;
    }

    private LocalDate currentPeriod() {
        return LocalDate.ofInstant(Instant.now(clock), ZoneOffset.UTC).withDayOfMonth(1);
    }

    /** Aplica los limites del plan al periodo vigente (crea o actualiza las filas de cuota). */
    @Transactional
    public void applyPlanLimits(UUID tenantId, Plan plan) {
        LocalDate period = currentPeriod();
        for (Map.Entry<String, Long> e : plan.quotaLimits().entrySet()) {
            Optional<QuotaPeriod> existing = quotas.find(tenantId, e.getKey(), period);
            if (existing.isPresent()) {
                quotas.updateLimit(existing.get().id(), e.getValue());
            } else {
                quotas.insert(tenantId, e.getKey(), period, e.getValue());
            }
        }
    }

    @Transactional
    public void record(UUID tenantId, String metricName, long value) {
        if (metricName == null) {
            throw new BadRequestException("Metrica requerida");
        }
        MetricName metric;
        try {
            metric = MetricName.valueOf(metricName);
        } catch (IllegalArgumentException e) {
            throw new BadRequestException("Metrica desconocida");
        }
        if (value <= 0) {
            throw new BadRequestException("El consumo debe ser positivo");
        }
        Tenant tenant = tenants.find(tenantId).orElseThrow(() -> new NotFoundException("Tenant no encontrado"));
        if (tenant.status() != TenantStatus.ACTIVE) {
            throw new ConflictException("El tenant no esta activo");
        }
        LocalDate period = currentPeriod();
        quotas.insertConsumption(tenantId, metric.name(), value, Instant.now(clock));
        events.consumoRegistrado(tenantId, metric.name(), value);

        Optional<QuotaPeriod> row = quotas.find(tenantId, metric.name(), period);
        if (row.isEmpty()) {
            Plan plan = tenants.findPlan(tenant.planId()).orElseThrow();
            Long limit = plan.quotaLimits().get(metric.name());
            if (limit == null) {
                return; // metrica sin cuota en el plan: solo se registra el consumo
            }
            quotas.insert(tenantId, metric.name(), period, limit);
            row = quotas.find(tenantId, metric.name(), period);
        }
        QuotaPeriod q = row.orElseThrow();
        quotas.addConsumption(q.id(), value);
        if (quotas.markThresholdNotified(q.id(), thresholdPercent)) {
            events.cuotaUmbralAlcanzado(tenantId, metric.name(), thresholdPercent);
        }
    }
}
