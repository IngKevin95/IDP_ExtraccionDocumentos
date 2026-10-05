package com.idp.kms;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.Map;

/**
 * Verificacion local ed25519 con llaves publicas crudas por version. Acepta firmas con el formato de transit
 * ({@code vault:vN:<base64>}) o firmas crudas (version mas reciente).
 */
public final class Ed25519Verifier {

    private static final byte[] X509_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private Ed25519Verifier() {
    }

    public static boolean verify(Map<Integer, byte[]> rawKeysByVersion, byte[] data, byte[] signature) {
        if (rawKeysByVersion == null || rawKeysByVersion.isEmpty() || signature == null) {
            return false;
        }
        try {
            int version = rawKeysByVersion.keySet().stream().mapToInt(Integer::intValue).max().orElse(1);
            byte[] sig = signature;
            String text = new String(signature, StandardCharsets.UTF_8);
            if (text.startsWith("vault:v")) {
                String[] parts = text.split(":", 3);
                if (parts.length != 3) {
                    return false;
                }
                version = Integer.parseInt(parts[1].substring(1));
                sig = Base64.getDecoder().decode(parts[2]);
            }
            byte[] raw = rawKeysByVersion.get(version);
            if (raw == null || raw.length != 32) {
                return false;
            }
            byte[] encoded = new byte[X509_PREFIX.length + 32];
            System.arraycopy(X509_PREFIX, 0, encoded, 0, X509_PREFIX.length);
            System.arraycopy(raw, 0, encoded, X509_PREFIX.length, 32);
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(encoded)));
            s.update(data);
            return s.verify(sig);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }
}
