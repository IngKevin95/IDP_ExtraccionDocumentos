package com.idp.tenant.infrastructure.platform;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Implementacion declarativa por defecto: registra el recurso deseado de cada paso (y su compensacion) y
 * devuelve los identificadores determinísticos del silo. Los adaptadores reales (CNPG, bucket, OpenBao)
 * reemplazan este bean (@Primary) implementando {@link ProvisioningPort}; la base de control es la fuente de verdad.
 */
@Component
public class DeclarativeProvisioningAdapter implements ProvisioningPort {
    private static final Logger LOG = LoggerFactory.getLogger(DeclarativeProvisioningAdapter.class);

    private static String compact(UUID id) {
        return id.toString().replace("-", "");
    }

    @Override
    public String createDatabase(UUID tenantId) {
        String name = "tenant_" + compact(tenantId);
        LOG.info("provisioning desired database tenant={} name={}", tenantId, name);
        return name;
    }

    @Override
    public void dropDatabase(UUID tenantId) {
        LOG.info("provisioning compensate database tenant={}", tenantId);
    }

    @Override
    public String createBucket(UUID tenantId) {
        String name = "idp-" + tenantId + "-docs";
        LOG.info("provisioning desired bucket tenant={} name={}", tenantId, name);
        return name;
    }

    @Override
    public void deleteBucket(UUID tenantId) {
        LOG.info("provisioning compensate bucket tenant={}", tenantId);
    }

    @Override
    public KeyIds createKeys(UUID tenantId) {
        KeyIds keys = new KeyIds("tenants/" + tenantId + "/kek-data", "tenants/" + tenantId + "/kek-audit");
        LOG.info("provisioning desired keys tenant={} data={} audit={}", tenantId, keys.dataKekId(), keys.auditKekId());
        return keys;
    }

    @Override
    public void scheduleKeysDeletion(UUID tenantId, KeyIds keys) {
        LOG.info("provisioning compensate keys tenant={}", tenantId);
    }

    @Override
    public String createOpenBaoRole(UUID tenantId) {
        String role = "tenant-" + tenantId;
        LOG.info("provisioning desired openbao role tenant={} role={}", tenantId, role);
        return role;
    }

    @Override
    public void deleteOpenBaoRole(UUID tenantId) {
        LOG.info("provisioning compensate openbao role tenant={}", tenantId);
    }
}
