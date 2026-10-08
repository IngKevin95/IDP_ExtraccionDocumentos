package com.idp.kms.azure;

import com.idp.kms.KeyService;

/**
 * Interfaz fina sobre las operaciones de Key Vault que usa el adaptador. Recibe siempre el nombre de llave ya
 * hasheado. Una llave inexistente se reporta como {@link KeyService.KeyNotFoundException}; cualquier otro fallo
 * como RuntimeException (el adaptador lo traduce a {@code KeyServiceUnavailableException}).
 */
interface KeyVaultApi {

    /** wrapKey con RSA-OAEP-256 sobre la llave RSA. */
    byte[] wrap(String keyName, byte[] material);

    byte[] unwrap(String keyName, byte[] wrapped);

    /** Firma ES256 de {@code data} (el proveedor calcula SHA-256); devuelve r||s crudo de 64 bytes. */
    byte[] sign(String keyName, byte[] data);

    boolean verify(String keyName, byte[] data, byte[] signature);

    /** updateKeyProperties(enabled=false) y beginDeleteKey; el purgado lo gobierna la purge protection. */
    void disable(String keyName);
}
