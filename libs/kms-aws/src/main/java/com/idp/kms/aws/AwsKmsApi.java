package com.idp.kms.aws;

import java.util.Map;

/**
 * Interfaz fina sobre las llamadas de AWS KMS que usa el adaptador. Se identifica la llave por su alias.
 * Las implementaciones lanzan {@code KeyService.KeyNotFoundException} y {@code KeyService.KeyDisabledException}
 * para esos casos; cualquier otro fallo es una RuntimeException que {@link AwsKmsKeyService} reporta como
 * indisponibilidad del proveedor.
 */
interface AwsKmsApi {

    byte[] encrypt(String alias, byte[] plaintext, Map<String, String> encryptionContext);

    byte[] decrypt(String alias, byte[] ciphertext, Map<String, String> encryptionContext);

    byte[] sign(String alias, byte[] message);

    boolean verify(String alias, byte[] message, byte[] signature);

    /** SubjectPublicKeyInfo DER de la llave de firma. */
    byte[] publicKey(String alias);

    /** DisableKey y ScheduleKeyDeletion (DisableKey y ScheduleKeyDeletion no admiten alias: se resuelve el id). */
    void disableAndScheduleDeletion(String alias, int pendingWindowInDays);

    /** Crea la llave (de firma Ed25519 o simetrica de datos) y su alias; no hace nada si el alias ya existe. */
    void createKey(String alias, boolean signing);
}
