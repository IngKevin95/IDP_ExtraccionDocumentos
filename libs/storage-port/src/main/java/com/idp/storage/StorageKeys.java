package com.idp.storage;

import com.idp.storage.ObjectStore.StorageException;
import com.idp.tenant.TenantId;
import java.util.Arrays;
import java.util.HexFormat;

/** Reglas de aislamiento y validacion compartidas por los adaptadores de almacenamiento. */
public final class StorageKeys {

    private StorageKeys() {
    }

    /** Clave del objeto con aislamiento por tenant; rechaza rutas que escapen del prefijo. */
    public static String key(TenantId tenantId, String path) {
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

    /** Decodifica un SHA-256 hexadecimal de 32 bytes. */
    public static byte[] sha256Bytes(String hexSha256) {
        try {
            byte[] raw = HexFormat.of().parseHex(hexSha256);
            if (raw.length != 32) {
                throw new StorageException("SHA-256 invalido");
            }
            return raw;
        } catch (IllegalArgumentException e) {
            throw new StorageException("SHA-256 invalido");
        }
    }
}
