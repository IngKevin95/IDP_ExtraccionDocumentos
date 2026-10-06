package com.idp.notification.service;

import com.idp.kms.EnvelopeCiphertext;
import com.idp.kms.EnvelopeCrypto;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantKeyResolver;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

/**
 * Generacion y custodia de los secretos HMAC (SEC-028, ADR 0009): se generan con SecureRandom, se muestran una
 * sola vez y se almacenan cifrados en sobre con la KEK de datos del tenant. El AAD liga el cifrado a tenant y
 * webhook, de modo que un secreto copiado a otra suscripcion no se puede descifrar.
 */
public class WebhookSecrets {

    private static final int SECRET_BYTES = 32;

    private final EnvelopeCrypto crypto;
    private final TenantKeyResolver keys;
    private final SecureRandom random = new SecureRandom();

    public WebhookSecrets(EnvelopeCrypto crypto, TenantKeyResolver keys) {
        this.crypto = crypto;
        this.keys = keys;
    }

    /** Secreto aleatorio de 256 bits en base64url sin relleno. */
    public String generate() {
        byte[] raw = new byte[SECRET_BYTES];
        random.nextBytes(raw);
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        } finally {
            Arrays.fill(raw, (byte) 0);
        }
    }

    public String seal(UUID tenantId, UUID webhookId, String secret) {
        String tenant = tenantId.toString();
        EnvelopeCiphertext c = crypto.encrypt(new TenantId(tenant), keys.resolve(tenant).dataKekId(),
            aad(tenantId, webhookId), secret.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(c.toBytes());
    }

    public String open(UUID tenantId, UUID webhookId, String sealed) {
        String tenant = tenantId.toString();
        byte[] plain = crypto.decrypt(new TenantId(tenant), keys.resolve(tenant).dataKekId(), aad(tenantId, webhookId),
            EnvelopeCiphertext.fromBytes(Base64.getDecoder().decode(sealed)));
        try {
            return new String(plain, StandardCharsets.UTF_8);
        } finally {
            Arrays.fill(plain, (byte) 0);
        }
    }

    private static Map<String, String> aad(UUID tenantId, UUID webhookId) {
        return Map.of("tenant", tenantId.toString(), "webhook", webhookId.toString(), "purpose", "webhook-secret");
    }
}
