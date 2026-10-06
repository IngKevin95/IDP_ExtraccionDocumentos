package com.idp.notification.domain;

/** Estado de una entrega. FALLIDO equivale al DLT: solo sale con reintento manual. */
public enum DeliveryStatus {
    PENDIENTE,
    ENTREGADO,
    FALLIDO,
    CANCELADO
}
