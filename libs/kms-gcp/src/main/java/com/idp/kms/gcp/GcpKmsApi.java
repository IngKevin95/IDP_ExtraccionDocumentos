package com.idp.kms.gcp;

/**
 * Superficie minima de Cloud KMS que usa el adaptador. {@code cryptoKey} es el nombre completo
 * {@code projects/<p>/locations/<l>/keyRings/<r>/cryptoKeys/<k>}. Las implementaciones lanzan
 * {@code KeyService.KeyNotFoundException} si la llave no existe y cualquier otra {@code RuntimeException} ante
 * un fallo del proveedor.
 */
interface GcpKmsApi {

    /** Llave publica (SubjectPublicKeyInfo DER) de la version de firma vigente y su numero. */
    record SigningKey(int version, byte[] spki) {
    }

    byte[] encrypt(String cryptoKey, byte[] plaintext, byte[] aad);

    byte[] decrypt(String cryptoKey, byte[] ciphertext, byte[] aad);

    /** Firma los datos crudos (Ed25519) con la version de firma vigente de la llave. */
    byte[] sign(String cryptoKey, byte[] data);

    SigningKey signingKey(String cryptoKey);

    /** Deshabilita todas las versiones y programa su destruccion en la ventana de la llave. */
    void disable(String cryptoKey);
}
