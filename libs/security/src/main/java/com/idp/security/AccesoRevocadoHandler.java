package com.idp.security;

import com.idp.events.EventEnvelope;
import java.util.function.Consumer;

/** Invalida la cache de roles al llegar un evento {@code acceso.revocado}. Conectar a un consumidor idempotente. */
public final class AccesoRevocadoHandler implements Consumer<EventEnvelope> {

    public static final String EVENT_TYPE = "acceso.revocado";

    private final CachingRoleAssignmentVerifier verifier;

    public AccesoRevocadoHandler(CachingRoleAssignmentVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    public void accept(EventEnvelope event) {
        if (!EVENT_TYPE.equals(event.eventType()) || event.payload() == null) {
            return;
        }
        String subject = event.payload().path("subjectId").asText("");
        if (!subject.isEmpty()) {
            verifier.invalidateUser(event.tenantId().toString(), subject);
        }
    }
}
