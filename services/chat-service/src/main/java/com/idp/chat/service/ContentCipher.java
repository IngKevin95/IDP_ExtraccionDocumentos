package com.idp.chat.service;

import com.idp.chat.service.Exceptions.ContentUnavailableException;
import com.idp.kms.EnvelopeCiphertext;
import com.idp.kms.EnvelopeCrypto;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantKeyResolver;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Cifrado de contenido del chat en reposo (SEC-015/SEC-018): texto de los fragmentos, mensajes y citas se guarda como
 * sobre AES-GCM con una DEK por dato, envuelta con la KEK de datos del tenant (TenantKeyResolver). El AAD liga cada
 * cifrado a tenant, documento, registro y campo ({@code tenantId|documentId|recordId|field}): un blob no se puede
 * mover a otro registro ni a otro tenant. Con la KEK destruida (crypto-shredding) el texto queda ilegible.
 */
@Component
public class ContentCipher {

    public static final String FIELD_CHUNK = "chunk.content";
    public static final String FIELD_MESSAGE = "message.content";
    public static final String FIELD_CITATION = "citation.exact_quote";

    private final EnvelopeCrypto crypto;
    private final TenantKeyResolver keys;

    public ContentCipher(EnvelopeCrypto crypto, TenantKeyResolver keys) {
        this.crypto = crypto;
        this.keys = keys;
    }

    public byte[] encrypt(String tenantId, UUID documentId, UUID recordId, String field, String plain) {
        String kek = keys.resolve(tenantId).dataKekId();
        return crypto.encrypt(new TenantId(tenantId), kek, aad(tenantId, documentId, recordId, field),
                plain.getBytes(StandardCharsets.UTF_8)).toBytes();
    }

    /** @throws ContentUnavailableException contenido ausente, ilegible o con la KEK destruida o deshabilitada */
    public String decrypt(String tenantId, UUID documentId, UUID recordId, String field, byte[] enc) {
        if (enc == null || enc.length == 0) {
            throw new ContentUnavailableException();
        }
        try {
            String kek = keys.resolve(tenantId).dataKekId();
            return new String(crypto.decrypt(new TenantId(tenantId), kek, aad(tenantId, documentId, recordId, field),
                    EnvelopeCiphertext.fromBytes(enc)), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            throw new ContentUnavailableException(e);
        }
    }

    private static Map<String, String> aad(String tenantId, UUID documentId, UUID recordId, String field) {
        return Map.of("tenantId", tenantId, "documentId", documentId.toString(), "recordId", recordId.toString(),
                "field", field);
    }
}
