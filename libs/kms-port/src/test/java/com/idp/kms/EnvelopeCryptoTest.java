package com.idp.kms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class EnvelopeCryptoTest {

    private final TenantId tenant = new TenantId("t1");
    private final Map<String, String> aad = Map.of("doc", "d-1");
    private final byte[] plain = "oficio con datos sensibles".getBytes(StandardCharsets.UTF_8);

    @Test
    void ac01_cifraConDekYLaEnvuelveConLaKekDelTenant() {
        EnvelopeCrypto crypto = new EnvelopeCrypto(new InMemoryKeyService());
        EnvelopeCiphertext env = crypto.encrypt(tenant, "datos", aad, plain);

        assertFalse(Arrays.equals(plain, env.encryptedData()));
        assertArrayEquals(plain, crypto.decrypt(tenant, "datos", aad, env));
    }

    @Test
    void cadaCifradoUsaDekEIvDistintos() {
        EnvelopeCrypto crypto = new EnvelopeCrypto(new InMemoryKeyService());
        EnvelopeCiphertext a = crypto.encrypt(tenant, "datos", aad, plain);
        EnvelopeCiphertext b = crypto.encrypt(tenant, "datos", aad, plain);
        assertFalse(Arrays.equals(a.encryptedData(), b.encryptedData()));
        assertFalse(Arrays.equals(a.wrappedDek(), b.wrappedDek()));
    }

    @Test
    void aadDistintoOContenidoAlteradoFallaLaAutenticacion() {
        EnvelopeCrypto crypto = new EnvelopeCrypto(new InMemoryKeyService());
        EnvelopeCiphertext env = crypto.encrypt(tenant, "datos", aad, plain);
        assertThrows(IllegalArgumentException.class,
            () -> crypto.decrypt(tenant, "datos", Map.of("doc", "d-2"), env));

        byte[] tampered = env.encryptedData();
        tampered[tampered.length - 1] ^= 1;
        assertThrows(IllegalArgumentException.class,
            () -> crypto.decrypt(tenant, "datos", aad, new EnvelopeCiphertext(env.wrappedDek(), tampered)));
    }

    @Test
    void ac04_trasDisableKekNoSePuedeDescifrar() {
        InMemoryKeyService kms = new InMemoryKeyService();
        EnvelopeCrypto crypto = new EnvelopeCrypto(kms);
        EnvelopeCiphertext env = crypto.encrypt(tenant, "datos", aad, plain);
        kms.disableKek(tenant, "datos");
        assertThrows(KeyService.KeyDisabledException.class, () -> crypto.decrypt(tenant, "datos", aad, env));
    }

    @Test
    void ac08_laDekEnClaroSeSobrescribeAlTerminarElCifrado() {
        AtomicReference<byte[]> seen = new AtomicReference<>();
        KeyService spy = new InMemoryKeyService();
        KeyService delegating = new KeyService() {
            @Override
            public CryptoResult wrapDek(TenantId t, byte[] dek, String kek, Map<String, String> a) {
                seen.set(dek);
                return spy.wrapDek(t, dek, kek, a);
            }

            @Override
            public CryptoResult unwrapDek(TenantId t, byte[] w, String kek, Map<String, String> a) {
                return spy.unwrapDek(t, w, kek, a);
            }

            @Override
            public CryptoResult sign(TenantId t, byte[] d, String k) {
                return spy.sign(t, d, k);
            }

            @Override
            public boolean verify(TenantId t, byte[] d, byte[] s, String k) {
                return spy.verify(t, d, s, k);
            }

            @Override
            public void disableKek(TenantId t, String k) {
                spy.disableKek(t, k);
            }
        };

        new EnvelopeCrypto(delegating).encrypt(tenant, "datos", aad, plain);

        byte[] dek = seen.get();
        assertEquals(32, dek.length);
        for (byte b : dek) {
            assertEquals(0, b);
        }
    }

    @Test
    void sobreSerializadoRecuperaSusPartes() {
        EnvelopeCrypto crypto = new EnvelopeCrypto(new InMemoryKeyService());
        EnvelopeCiphertext env = crypto.encrypt(tenant, "datos", aad, plain);
        EnvelopeCiphertext back = EnvelopeCiphertext.fromBytes(env.toBytes());
        assertEquals(env, back);
        assertTrue(env.toString().contains("B"));
        assertThrows(IllegalArgumentException.class, () -> EnvelopeCiphertext.fromBytes(new byte[] {0, 0, 1, 0}));
    }

    @Test
    void aadCanonicoNoEsAmbiguo() {
        assertFalse(Arrays.equals(AadContext.canonical(Map.of("a", "b=c")), AadContext.canonical(Map.of("a=b", "c"))));
        assertEquals(0, AadContext.canonical(null).length);
    }
}
