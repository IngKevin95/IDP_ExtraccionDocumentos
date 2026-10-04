package com.idp.kms;

import com.idp.tenant.TenantId;
import java.util.Map;

/**
 * Key Management Service (KMS) Port.
 * <p>
 * Crypto-shredding: Para destruir los datos de un tenant, se debe llamar a {@link #disableKek(TenantId, String)}.
 * Esto realiza un 'disableKek' inmediato (deshabilitando el uso de la KEK para descifrar)
 * y programa su destrucción definitiva por la ventana de retención del proveedor KMS.
 * </p>
 */
public interface KeyService {

    class CryptoResult {
        private final byte[] data;
        public CryptoResult(byte[] data) {
            this.data = data != null ? data.clone() : null;
        }
        public byte[] getData() {
            return data != null ? data.clone() : null;
        }
    }

    class KeyDisabledException extends RuntimeException {
        public KeyDisabledException(String message) { super(message); }
    }

    class KeyNotFoundException extends RuntimeException {
        public KeyNotFoundException(String message) { super(message); }
    }

    CryptoResult wrapDek(TenantId tenantId, byte[] dek, String kekId, Map<String, String> aadContext);
    CryptoResult unwrapDek(TenantId tenantId, byte[] wrappedDek, String kekId, Map<String, String> aadContext);
    CryptoResult sign(TenantId tenantId, byte[] data, String keyId);
    boolean verify(TenantId tenantId, byte[] data, byte[] signature, String keyId);
    
    /**
     * Disable a KEK immediately and schedule it for deletion.
     */
    void disableKek(TenantId tenantId, String kekId);
}
