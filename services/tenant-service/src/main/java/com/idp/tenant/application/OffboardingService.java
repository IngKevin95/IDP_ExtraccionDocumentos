package com.idp.tenant.application;

import com.idp.kms.KeyService;
import com.idp.tenant.TenantId;
import com.idp.tenant.domain.Exceptions.ConflictException;
import com.idp.tenant.domain.Exceptions.NotFoundException;
import com.idp.tenant.domain.Tenant;
import com.idp.tenant.domain.TenantConfig;
import com.idp.tenant.domain.TenantStatus;
import com.idp.tenant.context.LegalHoldGate;
import com.idp.tenant.infrastructure.persistence.LegalHoldRepository;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Baja en dos fases (SEC-047): 1) PENDING_DELETION con plazo y tenant.baja_iniciada; 2) job de shredding que
 * deshabilita la KEK de datos (SEC-016) conservando la de auditoria. Bloqueada por legal hold (SEC-017).
 */
@Service
public class OffboardingService {
    private static final Logger LOG = LoggerFactory.getLogger(OffboardingService.class);

    private final TenantRepository tenants;
    private final TenantEvents events;
    private final KeyService keyService;
    private final LegalHoldGate holds;
    private final LegalHoldRepository tenantHolds;
    private final Clock clock;
    private final long graceDays;

    public OffboardingService(TenantRepository tenants, TenantEvents events, KeyService keyService,
                              LegalHoldGate holds, LegalHoldRepository tenantHolds, Clock clock,
                              @Value("${idp.tenant.offboarding.grace-days:30}") long graceDays) {
        this.holds = holds;
        this.tenantHolds = tenantHolds;
        this.tenants = tenants;
        this.events = events;
        this.keyService = keyService;
        this.clock = clock;
        this.graceDays = graceDays;
    }

    @Transactional
    public Tenant initiate(UUID id) {
        Tenant tenant = tenants.find(id).orElseThrow(() -> new NotFoundException("Tenant no encontrado"));
        if (tenant.status() == TenantStatus.PENDING_DELETION) {
            return tenant;
        }
        if (tenant.status() != TenantStatus.ACTIVE && tenant.status() != TenantStatus.FAILED) {
            throw new ConflictException("El tenant no admite baja en su estado actual");
        }
        if (!tenantHolds.findActive(id).isEmpty()) {
            throw new ConflictException("El tenant tiene legal hold activo");
        }
        Instant now = Instant.now(clock);
        tenants.updateStatus(id, TenantStatus.PENDING_DELETION, now.plus(Duration.ofDays(graceDays)), now);
        events.bajaIniciada(id);
        return tenants.find(id).orElseThrow();
    }

    /** Precondiciones de la baja (404/409) para validarlas antes de registrar la solicitud de aprobacion (A3). */
    @Transactional(readOnly = true)
    public Tenant assertCanInitiate(UUID id) {
        Tenant tenant = tenants.find(id).orElseThrow(() -> new NotFoundException("Tenant no encontrado"));
        if (tenant.status() == TenantStatus.PENDING_DELETION) {
            return tenant;
        }
        if (tenant.status() != TenantStatus.ACTIVE && tenant.status() != TenantStatus.FAILED) {
            throw new ConflictException("El tenant no admite baja en su estado actual");
        }
        if (!tenantHolds.findActive(id).isEmpty()) {
            throw new ConflictException("El tenant tiene legal hold activo");
        }
        return tenant;
    }

    /** Fase 2: ejecuta el shredding de los tenants con plazo vencido. Devuelve cuantos se destruyeron. */
    public int shredDueTenants() {
        int shredded = 0;
        for (Tenant t : tenants.findPendingDeletionDue(Instant.now(clock))) {
            if (shred(t)) {
                shredded++;
            }
        }
        return shredded;
    }

    private boolean shred(Tenant tenant) {
        UUID id = tenant.id();
        TenantConfig config = tenants.findConfig(id).orElse(null);
        try {
            if (holds.anyHold(id.toString())) {
                LOG.info("shredding omitido por legal hold (tenant o documento) tenant={}", id);
                return false;
            }
        } catch (RuntimeException e) {
            LOG.warn("shredding omitido: no se pudo consultar legal hold, se reintenta tenant={}", id);
            return false;
        }
        try {
            if (config != null && config.dataKekId() != null) {
                keyService.disableKek(new TenantId(id.toString()), config.dataKekId());
            }
        } catch (KeyService.KeyNotFoundException e) {
            // La KEK ya no existe: el shredding ya ocurrio (reintento tras fallo parcial). Exito idempotente.
            LOG.info("KEK de datos ya inexistente, shredding idempotente tenant={}", id);
        } catch (IllegalArgumentException | UnsupportedOperationException e) {
            // Error terminal (KEK invalida o KMS sin configurar): reintentar no lo arregla. Se saca de la cola.
            LOG.error("ALERTA shredding fallido terminal, requiere intervencion tenant={} causa={}", id,
                    e.getClass().getSimpleName());
            tenants.updateStatus(id, TenantStatus.FAILED, tenant.deletionDueAt(), Instant.now(clock));
            return false;
        } catch (RuntimeException e) {
            LOG.warn("shredding fallido, se reintenta tenant={}", id);
            return false;
        }
        tenants.updateStatus(id, TenantStatus.DELETED, tenant.deletionDueAt(), Instant.now(clock));
        return true;
    }
}
