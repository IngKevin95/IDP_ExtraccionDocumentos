package com.idp.tenant.application;

import com.idp.tenant.domain.Exceptions.BadRequestException;
import com.idp.tenant.domain.Plan;
import com.idp.tenant.domain.Tenant;
import com.idp.tenant.domain.TenantConfig;
import com.idp.tenant.domain.TenantStatus;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import com.idp.tenant.infrastructure.platform.ProvisioningPort;
import com.idp.tenant.infrastructure.platform.ProvisioningPort.KeyIds;
import com.idp.tenant.infrastructure.platform.ProvisioningPort.ProvisioningException;
import com.idp.tenant.infrastructure.platform.ProvisioningPort.Reason;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Alta de tenant: registra el tenant en la base de control (CREATING), ejecuta los pasos declarativos del
 * silo (base de datos, bucket, llaves, rol OpenBao) y los compensa en orden inverso si alguno falla.
 * Emite tenant.aprovisionado o tenant.aprovisionamiento_fallido por outbox (SEC-001, SEC-015).
 */
@Service
public class TenantProvisioningService {
    private static final Logger LOG = LoggerFactory.getLogger(TenantProvisioningService.class);

    private record Compensation(String step, Runnable action) {}

    private final TenantRepository tenants;
    private final ProvisioningPort port;
    private final TenantEvents events;
    private final QuotaService quotas;
    private final TransactionTemplate tx;
    private final Clock clock;

    public TenantProvisioningService(TenantRepository tenants, ProvisioningPort port, TenantEvents events,
                                     QuotaService quotas, TransactionTemplate tx, Clock clock) {
        this.tenants = tenants;
        this.port = port;
        this.events = events;
        this.quotas = quotas;
        this.tx = tx;
        this.clock = clock;
    }

    public Tenant create(String name, UUID planId) {
        Plan plan = tenants.findPlan(planId).orElseThrow(() -> new BadRequestException("Plan inexistente"));
        UUID id = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        Instant now = Instant.now(clock);
        tx.executeWithoutResult(s -> {
            tenants.insert(new Tenant(id, name, TenantStatus.CREATING, planId, null, now, now));
            tenants.insertConfig(new TenantConfig(id, null, null, false, Map.of()));
        });

        Deque<Compensation> done = new ArrayDeque<>();
        try {
            String db = step(id, "database", () -> port.createDatabase(id), () -> port.dropDatabase(id), done);
            String bucket = step(id, "bucket", () -> port.createBucket(id), () -> port.deleteBucket(id), done);
            KeyIds keys = step(id, "keys", () -> port.createKeys(id), null, done);
            done.push(new Compensation("keys", () -> port.scheduleKeysDeletion(id, keys)));
            String role = step(id, "openbao-role", () -> port.createOpenBaoRole(id),
                    () -> port.deleteOpenBaoRole(id), done);
            tx.executeWithoutResult(s -> {
                tenants.updateKeks(id, keys.dataKekId(), keys.auditKekId());
                tenants.insertSilo(id, db, bucket, role);
                tenants.updateStatus(id, TenantStatus.ACTIVE, null, Instant.now(clock));
                quotas.applyPlanLimits(id, plan);
                events.aprovisionado(id, planId, correlationId);
            });
        } catch (RuntimeException e) {
            Reason reason = e instanceof ProvisioningException pe ? pe.reason() : Reason.INFRASTRUCTURE_ERROR;
            compensate(id, done);
            tx.executeWithoutResult(s -> {
                tenants.updateStatus(id, TenantStatus.FAILED, null, Instant.now(clock));
                events.aprovisionamientoFallido(id, reason.name(), correlationId);
            });
        }
        return tenants.find(id).orElseThrow();
    }

    private <T> T step(UUID id, String name, Supplier<T> action, Runnable compensation, Deque<Compensation> done) {
        try {
            T result = action.get();
            tenants.insertStep(id, name, "APPLY", "OK", null, Instant.now(clock));
            if (compensation != null) {
                done.push(new Compensation(name, compensation));
            }
            return result;
        } catch (RuntimeException e) {
            tenants.insertStep(id, name, "APPLY", "FAILED", e.getMessage(), Instant.now(clock));
            throw e;
        }
    }

    private void compensate(UUID id, Deque<Compensation> done) {
        while (!done.isEmpty()) {
            Compensation c = done.pop();
            try {
                c.action().run();
                tenants.insertStep(id, c.step(), "COMPENSATE", "OK", null, Instant.now(clock));
            } catch (RuntimeException e) {
                LOG.warn("compensation failed tenant={} step={}", id, c.step());
                tenants.insertStep(id, c.step(), "COMPENSATE", "FAILED", e.getMessage(), Instant.now(clock));
            }
        }
    }
}
