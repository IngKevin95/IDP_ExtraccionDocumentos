package com.idp.kms.aws;

import com.idp.kms.KeyService;
import java.util.Arrays;
import java.util.Map;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.AlreadyExistsException;
import software.amazon.awssdk.services.kms.model.DisabledException;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyState;
import software.amazon.awssdk.services.kms.model.KeyUsageType;
import software.amazon.awssdk.services.kms.model.KmsInvalidSignatureException;
import software.amazon.awssdk.services.kms.model.KmsInvalidStateException;
import software.amazon.awssdk.services.kms.model.MessageType;
import software.amazon.awssdk.services.kms.model.NotFoundException;
import software.amazon.awssdk.services.kms.model.SigningAlgorithmSpec;

/** {@link AwsKmsApi} real sobre {@link KmsClient}. */
final class AwsKmsClientApi implements AwsKmsApi {

    private static final SigningAlgorithmSpec ED25519 = SigningAlgorithmSpec.ED25519_SHA_512;

    private final KmsClient kms;

    AwsKmsClientApi(KmsClient kms) {
        this.kms = kms;
    }

    private interface Call<T> {
        T run();
    }

    private static <T> T translate(Call<T> call) {
        try {
            return call.run();
        } catch (NotFoundException e) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
        } catch (DisabledException | KmsInvalidStateException e) {
            // Deshabilitada o pendiente de borrado, posiblemente desde otro proceso.
            throw new KeyService.KeyDisabledException("KEK deshabilitada");
        }
    }

    @Override
    public byte[] encrypt(String alias, byte[] plaintext, Map<String, String> encryptionContext) {
        return translate(() -> kms.encrypt(b -> b.keyId(alias).plaintext(SdkBytes.fromByteArray(plaintext))
            .encryptionContext(encryptionContext)).ciphertextBlob().asByteArray());
    }

    @Override
    public byte[] decrypt(String alias, byte[] ciphertext, Map<String, String> encryptionContext) {
        // keyId explicito: KMS rechaza el blob si no fue cifrado con esa llave (aislamiento por tenant).
        return translate(() -> kms.decrypt(b -> b.keyId(alias).ciphertextBlob(SdkBytes.fromByteArray(ciphertext))
            .encryptionContext(encryptionContext)).plaintext().asByteArray());
    }

    @Override
    public byte[] sign(String alias, byte[] message) {
        return translate(() -> kms.sign(b -> b.keyId(alias).message(SdkBytes.fromByteArray(message))
            .messageType(MessageType.RAW).signingAlgorithm(ED25519)).signature().asByteArray());
    }

    @Override
    public boolean verify(String alias, byte[] message, byte[] signature) {
        try {
            return translate(() -> kms.verify(b -> b.keyId(alias).message(SdkBytes.fromByteArray(message))
                .messageType(MessageType.RAW).signingAlgorithm(ED25519)
                .signature(SdkBytes.fromByteArray(signature))).signatureValid());
        } catch (KmsInvalidSignatureException e) {
            return false;
        }
    }

    @Override
    public byte[] publicKey(String alias) {
        return translate(() -> kms.getPublicKey(b -> b.keyId(alias)).publicKey().asByteArray());
    }

    @Override
    public void disableAndScheduleDeletion(String alias, int pendingWindowInDays) {
        String keyId = translate(() -> kms.describeKey(b -> b.keyId(alias)).keyMetadata().keyId());
        try {
            kms.disableKey(b -> b.keyId(keyId));
        } catch (NotFoundException e) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
        } catch (KmsInvalidStateException e) {
            // Ya deshabilitada o pendiente de borrado (otra replica): es el estado buscado.
            if (!stateIn(keyId, KeyState.DISABLED, KeyState.PENDING_DELETION)) {
                throw e;
            }
        }
        try {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(pendingWindowInDays));
        } catch (NotFoundException e) {
            throw new KeyService.KeyNotFoundException("Llave inexistente en el KMS");
        } catch (KmsInvalidStateException e) {
            if (!stateIn(keyId, KeyState.PENDING_DELETION)) {
                throw e;
            }
        }
    }

    private boolean stateIn(String keyId, KeyState... states) {
        KeyState current = kms.describeKey(b -> b.keyId(keyId)).keyMetadata().keyState();
        return Arrays.asList(states).contains(current);
    }

    @Override
    public void createKey(String alias, boolean signing) {
        try {
            kms.describeKey(b -> b.keyId(alias));
            return;
        } catch (NotFoundException e) {
            // no existe todavia: se crea
        }
        String keyId = kms.createKey(b -> {
            if (signing) {
                b.keySpec(KeySpec.ECC_NIST_EDWARDS25519).keyUsage(KeyUsageType.SIGN_VERIFY);
            }
        }).keyMetadata().keyId();
        try {
            kms.createAlias(b -> b.aliasName(alias).targetKeyId(keyId));
        } catch (AlreadyExistsException e) {
            kms.scheduleKeyDeletion(b -> b.keyId(keyId).pendingWindowInDays(7));
        }
    }
}
