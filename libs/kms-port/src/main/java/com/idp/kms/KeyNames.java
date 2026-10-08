package com.idp.kms;

import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Nombres de llave por (tenant, keyId) para proveedores con limites de nombre estrictos (Key Vault: sin guion
 * bajo; Cloud KMS: 63 caracteres). Un SHA-256 del par separado por NUL no colisiona entre tenants, a diferencia de
 * transliterar caracteres ({@code a_b} y {@code a-b} darian el mismo nombre).
 */
public final class KeyNames {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]{1,64}");

    private KeyNames() {
    }

    /**
     * Valida los identificadores y devuelve {@code idp-<59 hex del SHA-256>}: 63 caracteres, solo [a-z0-9-], el
     * maximo de un id de CryptoKey de Cloud KMS (236 bits de hash, sin riesgo practico de colision).
     */
    public static String hashed(TenantId tenantId, String keyId) {
        check(tenantId, keyId);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((tenantId.value() + '\0' + keyId).getBytes(StandardCharsets.UTF_8));
            return "idp-" + HexFormat.of().formatHex(digest).substring(0, 59);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Mismo criterio de identificadores que {@code OpenBaoTransitKeyService} e {@code InMemoryKeyService} (AC-14). */
    public static void check(TenantId tenantId, String keyId) {
        if (tenantId == null || !NAME.matcher(tenantId.value()).matches()
                || keyId == null || !NAME.matcher(keyId).matches()) {
            throw new IllegalArgumentException("Identificador de tenant o llave invalido");
        }
    }
}
