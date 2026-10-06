package com.idp.security;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idp.events.EventSerde;
import com.idp.tenant.context.TenantDataSourceRouter;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RevocationListenersTest {

    private static final String TENANT_TOPIC = "idp.tenant.events";
    private static final com.idp.events.EventOriginGuard GUARD = com.idp.events.EventOriginGuard.standalone();
    private final EventSerde serde = new EventSerde();
    private final UUID tenant = UUID.randomUUID();

    private String event(String type, String extra) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"" + type + "\",\"schemaVersion\":1,"
            + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"tenantId\":\"" + tenant + "\","
            + "\"correlationId\":\"" + UUID.randomUUID() + "\"" + extra + "}";
    }

    @Test
    void h10_accesoRevocadoInvalidaLaCacheDelUsuario() {
        RoleAssignmentSource source = mock(RoleAssignmentSource.class);
        when(source.hasRole(anyString(), anyString(), anyString())).thenReturn(true);
        CachingRoleAssignmentVerifier verifier = new CachingRoleAssignmentVerifier(source);
        AccesoRevocadoKafkaListener listener = new AccesoRevocadoKafkaListener(verifier, serde, GUARD);

        verifier.hasRole(tenant.toString(), "u1", "R");
        verifier.hasRole(tenant.toString(), "u1", "R");
        verify(source, times(1)).hasRole(tenant.toString(), "u1", "R");

        listener.onMessage(event("otro.evento", ",\"subjectId\":\"u1\""), TENANT_TOPIC);
        verifier.hasRole(tenant.toString(), "u1", "R");
        verify(source, times(1)).hasRole(tenant.toString(), "u1", "R");

        listener.onMessage(event("acceso.revocado", ",\"subjectId\":\"u1\""), TENANT_TOPIC);
        verifier.hasRole(tenant.toString(), "u1", "R");
        verify(source, times(2)).hasRole(tenant.toString(), "u1", "R");
        listener.onMessage("no es json", TENANT_TOPIC);
    }

    @Test
    void sec052_eventoPorTopicoAjenoSeIgnora() {
        RoleAssignmentSource source = mock(RoleAssignmentSource.class);
        when(source.hasRole(anyString(), anyString(), anyString())).thenReturn(true);
        CachingRoleAssignmentVerifier verifier = new CachingRoleAssignmentVerifier(source);
        AccesoRevocadoKafkaListener revoked = new AccesoRevocadoKafkaListener(verifier, serde, GUARD);
        TenantDataSourceRouter router = mock(TenantDataSourceRouter.class);
        TenantPoolEvictionKafkaListener evict = new TenantPoolEvictionKafkaListener(router, serde, GUARD);

        verifier.hasRole(tenant.toString(), "u1", "R");
        revoked.onMessage(event("acceso.revocado", ",\"subjectId\":\"u1\""), "documentos.eventos");
        verifier.hasRole(tenant.toString(), "u1", "R");
        verify(source, times(1)).hasRole(tenant.toString(), "u1", "R");

        evict.onMessage(event("tenant.baja_iniciada", ""), "auditoria.senales");
        verify(router, never()).evict(anyString());
    }

    @Test
    void h7_bajaIniciadaDesalojaElPoolDelTenant() {
        TenantDataSourceRouter router = mock(TenantDataSourceRouter.class);
        TenantPoolEvictionKafkaListener listener = new TenantPoolEvictionKafkaListener(router, serde, GUARD);

        listener.onMessage(event("tenant.aprovisionado", ""), TENANT_TOPIC);
        verify(router, never()).evict(anyString());

        listener.onMessage(event("tenant.baja_iniciada", ""), TENANT_TOPIC);
        verify(router).evict(tenant.toString());
        listener.onMessage("{", TENANT_TOPIC);
    }
}
