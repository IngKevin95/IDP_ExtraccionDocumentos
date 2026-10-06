package com.idp.security.breakglass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BreakGlassManagerTest {

    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final BreakGlassManager manager = new BreakGlassManager(publisher, mapper);

    @Test
    void rejectsGrantWhenSubjectEqualsApprovedBy() {
        UUID tenantId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        assertThrows(IllegalArgumentException.class, () -> 
            manager.grant(tenantId, "userA", "userA", expiresAt));
    }

    @Test
    void publishesGrantEvent() {
        UUID tenantId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        manager.grant(tenantId, "userA", "userB", expiresAt);
        verify(publisher).publish(eq(tenantId.toString()), any(EventEnvelope.class));
    }

    @Test
    void publishesExpireEvent() {
        UUID tenantId = UUID.randomUUID();
        manager.expire(tenantId, "userA");
        verify(publisher).publish(eq(tenantId.toString()), any(EventEnvelope.class));
    }
}
