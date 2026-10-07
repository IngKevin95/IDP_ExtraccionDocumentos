package com.idp.storage.azure;

import com.idp.storage.ImmutableStore;
import com.idp.storage.ObjectKeys;
import com.idp.storage.ObjectMetadata;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantBucketResolver;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * ImmutableStore con immutable storage a nivel de version: retencion por blob (immutability policy) y legal hold.
 * El contenedor debe crearse con immutable storage con versionado; un borrado o sobrescritura solo crea una
 * version nueva y las versiones bajo retencion o hold no se pueden eliminar. Con contenedor fijo la validacion
 * ocurre al construir (arranque); con resolver por tenant, la primera vez que se usa cada contenedor.
 */
public class AzureBlobImmutableStore extends AzureBlobObjectStore implements ImmutableStore {

    private final Clock clock;
    private final Set<String> validated = ConcurrentHashMap.newKeySet();

    /** Contenedor WORM unico; falla de inmediato si no tiene immutable storage con versionado (AC-09). */
    public static AzureBlobImmutableStore forContainer(BlobApi api, String container, Clock clock) {
        AzureBlobImmutableStore store = new AzureBlobImmutableStore(api, TenantBucketResolver.fixed(container), clock);
        store.validate(container);
        return store;
    }

    public AzureBlobImmutableStore(BlobApi api, TenantBucketResolver containers, Clock clock) {
        super(api, containers);
        this.clock = clock;
    }

    /** Falla con IllegalStateException; el llamador decide si es arranque o uso. */
    private void validate(String container) {
        if (validated.contains(container)) {
            return;
        }
        boolean enabled;
        try {
            enabled = api.versionLevelWormEnabled(container);
        } catch (RuntimeException e) {
            throw new IllegalStateException("No se pudo verificar el contenedor WORM '" + container + "': "
                + e.getClass().getSimpleName(), e);
        }
        if (!enabled) {
            throw new IllegalStateException("El contenedor '" + container
                + "' no tiene habilitado immutable storage con versionado (WORM por version)");
        }
        validated.add(container);
    }

    /** En uso (por tenant) el fallo sale como StorageException, sin nombre de contenedor en el mensaje. */
    private void validateForUse(String container) {
        try {
            validate(container);
        } catch (IllegalStateException e) {
            throw new StorageException("El contenedor del tenant no cumple el modo WORM por version", e);
        }
    }

    @Override
    public void putWithRetention(TenantId tenantId, String path, InputStream data, ObjectMetadata metadata,
                                 Duration retention) {
        ObjectKeys.of(tenantId, path);
        if (retention == null || retention.isZero() || retention.isNegative()) {
            throw new StorageException("La retencion debe ser positiva");
        }
        validateForUse(container(tenantId));
        putInternal(tenantId, path, data, metadata, clock.instant().plus(retention));
    }

    @Override
    public void applyLegalHold(TenantId tenantId, String path) {
        setLegalHold(tenantId, path, true);
    }

    @Override
    public void removeLegalHold(TenantId tenantId, String path) {
        setLegalHold(tenantId, path, false);
    }

    private void setLegalHold(TenantId tenantId, String path, boolean hold) {
        String blob = ObjectKeys.of(tenantId, path);
        String container = container(tenantId);
        validateForUse(container);
        try {
            api.setLegalHold(container, blob, hold);
        } catch (BlobApi.NotFoundException e) {
            throw new StorageException("Objeto no encontrado");
        } catch (RuntimeException e) {
            throw new StorageException("No se pudo cambiar el legal hold: " + e.getClass().getSimpleName(), e);
        }
    }
}
