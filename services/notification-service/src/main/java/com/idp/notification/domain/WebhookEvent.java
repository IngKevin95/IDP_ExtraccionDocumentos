package com.idp.notification.domain;

import java.util.Arrays;
import java.util.Optional;

/** Eventos de dominio que se notifican al integrador (nombre en el contrato de eventos). */
public enum WebhookEvent {
    EXTRACCION_APROBADA("extraccion.aprobada"),
    DOCUMENTO_RECHAZADO("documento.rechazado"),
    REVISION_COMPLETADA("revision.completada");

    private final String wireName;

    WebhookEvent(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<WebhookEvent> fromWire(String name) {
        return Arrays.stream(values()).filter(e -> e.wireName.equals(name)).findFirst();
    }
}
