package com.idp.kms.gcp;

import java.util.Map;

/**
 * Superficie minima de Cloud KMS que usa el adaptador. {@code cryptoKey} es el nombre completo
 * {@code projects/<p>/locations/<l>/keyRings/<r>/cryptoKeys/<k>}. Las implementaciones lanzan
 * {@code KeyNotFoundException} si la llave no existe, {@code KeyDisabledException} si la version esta
 * deshabilitada o destruida, {@code IllegalArgumentException} si un decrypt recibe texto cifrado o AAD invalidos
 * y cualquier otra {@code RuntimeException} ante un fallo del proveedor.
 */
interface GcpKmsApi {

    /** Firma y la version de la CryptoKey que la produjo. */
    record Signed(int version, byte[] signature) {
    }

    byte[] encrypt(String cryptoKey, byte[] plaintext, byte[] aad);

    byte[] decrypt(String cryptoKey, byte[] ciphertext, byte[] aad);

    /** Firma los datos crudos (Ed25519, maximo 64 KiB) con la version de firma vigente de la llave. */
    Signed sign(String cryptoKey, byte[] data);

    /** Llaves publicas (SubjectPublicKeyInfo DER) de todas las versiones habilitadas, por numero de version. */
    Map<Integer, byte[]> publicKeys(String cryptoKey);

    /** Deshabilita todas las versiones y programa su destruccion en la ventana de la llave. */
    void disable(String cryptoKey);
}
