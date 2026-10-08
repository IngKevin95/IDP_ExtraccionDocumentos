package com.idp.kms.azure;

import com.idp.kms.KeyService;

/**
 * Interfaz fina sobre las operaciones de Key Vault que usa el adaptador. Recibe siempre el nombre de llave ya
 * hasheado. Contrato de errores: llave inexistente es {@link KeyService.KeyNotFoundException}; llave deshabilitada
 * o eliminada es {@link KeyService.KeyDisabledException}; texto cifrado rechazado en unwrap es
 * {@link CiphertextRejectedException}; cualquier otro fallo es RuntimeException (el adaptador lo traduce a
 * {@code KeyServiceUnavailableException}).
 */
interface KeyVaultApi {

    /** @param version version de la llave con la que se envolvio */
    record Wrapped(byte[] ciphertext, String version) { }

    /** wrapKey con RSA-OAEP-256 sobre la version vigente de la llave RSA. */
    Wrapped wrap(String keyName, byte[] material);

    /** unwrapKey contra la version indicada (la que uso wrap), para sobrevivir a la rotacion. */
    byte[] unwrap(String keyName, String version, byte[] wrapped);

    /** Firma ES256 de {@code data} (el proveedor calcula SHA-256); devuelve r||s crudo de 64 bytes. */
    byte[] sign(String keyName, byte[] data);

    boolean verify(String keyName, byte[] data, byte[] signature);

    /**
     * updateKeyProperties(enabled=false) y beginDeleteKey, esperando el borrado. Idempotente: una llave ya
     * inexistente o ya eliminada converge a exito.
     */
    void disable(String keyName);
}
