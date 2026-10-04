package com.idp.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.events.EventEnvelope;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CachingRoleAssignmentVerifierTest {

    private static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration d) {
            now = now.plus(d);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final RoleAssignmentSource source = mock(RoleAssignmentSource.class);
    private final MutableClock clock = new MutableClock();
    private final CachingRoleAssignmentVerifier verifier =
        new CachingRoleAssignmentVerifier(source, clock, Duration.ofSeconds(30));

    @Test
    void ac03_peticionesDentroDelTtlUsanLaCacheSinConsultarLaFuente() {
        when(source.hasRole("t1", "u1", "ANALISTA")).thenReturn(true);

        for (int i = 0; i < 5; i++) {
            clock.advance(Duration.ofSeconds(2));
            assertTrue(verifier.hasRole("t1", "u1", "ANALISTA"));
        }

        verify(source, times(1)).hasRole("t1", "u1", "ANALISTA");
    }

    @Test
    void ac04_tras30sLaCacheExpiraYSeRevalidaContraLaFuente() {
        when(source.hasRole("t1", "u1", "ANALISTA")).thenReturn(true, false);

        assertTrue(verifier.hasRole("t1", "u1", "ANALISTA"));
        clock.advance(Duration.ofSeconds(31));

        assertFalse(verifier.hasRole("t1", "u1", "ANALISTA"));
        verify(source, times(2)).hasRole("t1", "u1", "ANALISTA");
    }

    @Test
    void ac04_eventoAccesoRevocadoInvalidaLaCacheDeInmediato() {
        when(source.hasRole("t1", "u1", "ANALISTA")).thenReturn(true, false);
        assertTrue(verifier.hasRole("t1", "u1", "ANALISTA"));
        UUID tenant = UUID.nameUUIDFromBytes("x".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        when(source.hasRole(tenant.toString(), "u9", "ADMIN")).thenReturn(true, false);
        assertTrue(verifier.hasRole(tenant.toString(), "u9", "ADMIN"));

        new AccesoRevocadoHandler(verifier).accept(new EventEnvelope(UUID.randomUUID(), "acceso.revocado", 1,
            Instant.now(), tenant, UUID.randomUUID(), new ObjectMapper().createObjectNode().put("subjectId", "u9")));

        assertFalse(verifier.hasRole(tenant.toString(), "u9", "ADMIN"));
        assertTrue(verifier.hasRole("t1", "u1", "ANALISTA")); // otro usuario sigue en cache
        verify(source, times(1)).hasRole("t1", "u1", "ANALISTA");
    }

    @Test
    void handlerIgnoraEventosDeOtroTipo() {
        when(source.hasRole("t1", "u1", "R")).thenReturn(true);
        verifier.hasRole("t1", "u1", "R");
        UUID tenant = UUID.randomUUID();
        new AccesoRevocadoHandler(verifier).accept(new EventEnvelope(UUID.randomUUID(), "otro.evento", 1,
            Instant.now(), tenant, UUID.randomUUID(), new ObjectMapper().createObjectNode().put("subjectId", "u1")));
        assertTrue(verifier.size() == 1);
    }

    @Test
    void argumentosNulosSeDenieganSinConsultarLaFuente() {
        assertFalse(verifier.hasRole(null, "u", "r"));
        assertFalse(verifier.hasRole("t", null, "r"));
        assertFalse(verifier.hasRole("t", "u", null));
    }

    @Test
    void ttlMayorA30sSeRechaza() {
        assertThrows(IllegalArgumentException.class,
            () -> new CachingRoleAssignmentVerifier(source, clock, Duration.ofSeconds(31)));
    }
}
