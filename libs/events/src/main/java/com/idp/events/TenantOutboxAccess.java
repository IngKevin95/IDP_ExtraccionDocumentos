package com.idp.events;

import java.util.function.Function;

/** Ejecuta trabajo transaccional sobre el outbox del silo de un tenant. */
public interface TenantOutboxAccess {

    <T> T inTransaction(String tenantId, Function<OutboxRepository, T> work);
}
