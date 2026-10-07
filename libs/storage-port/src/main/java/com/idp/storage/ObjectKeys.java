package com.idp.storage;

import com.idp.storage.ObjectStore.StorageException;
import com.idp.tenant.TenantId;
import java.util.Arrays;

/** Clave de objeto con aislamiento por tenant, comun a todos los adaptadores. */
public final class ObjectKeys {

    private ObjectKeys() {
    }

    /** {@code <tenantId>/<path>}; rechaza tenants y rutas que escapen del prefijo. */
    public static String of(TenantId tenantId, String path) {
        String tenant = tenantId.value();
        if (!tenant.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new StorageException("tenantId invalido para almacenamiento");
        }
        if (path == null || path.isBlank() || path.startsWith("/") || path.contains("\\")
            || path.contains("//") || Arrays.asList(path.split("/")).contains("..")
            || Arrays.asList(path.split("/")).contains(".")) {
            throw new StorageException("Ruta de objeto invalida");
        }
        return tenant + "/" + path;
    }
}
