package com.idp.kms.contract;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

public abstract class KeyServiceContract {

    protected abstract KeyService getKms();
    protected abstract TenantId getTenantA();
    protected abstract TenantId getTenantB();

    /** Identificadores de llave que las suites usan; cada adaptador crea el tipo adecuado (firma o datos). */
    public static final String SIGNING_KEY = "firma";

    protected void createKeyIfNeeded(TenantId tenant, String keyId) {}

    protected Optional<Runnable> providerFailure() {
        return Optional.empty();
    }

    // Los rechazos de unwrap (AAD distinto, otro tenant) admiten cualquier RuntimeException: cada proveedor
    // reporta su propio tipo; lo que importa es que NO se devuelva la DEK.
    @Test
    protected void wrapYUnwrapRoundtrip() {
        TenantId t1 = getTenantA();
        createKeyIfNeeded(t1, "datos");
        byte[] dek = new byte[32];
        Arrays.fill(dek, (byte) 1);
        Map<String, String> aad = Map.of("doc", "d-1");
        
        byte[] wrapped = getKms().wrapDek(t1, dek, "datos", aad).getData();
        byte[] unwrapped = getKms().unwrapDek(t1, wrapped, "datos", aad).getData();
        assertArrayEquals(dek, unwrapped);
    }

    @Test
    protected void unwrapConAadDistintoFalla() {
        TenantId t1 = getTenantA();
        createKeyIfNeeded(t1, "datos");
        byte[] dek = new byte[32];
        byte[] wrapped = getKms().wrapDek(t1, dek, "datos", Map.of("doc", "d-1")).getData();
        assertThrows(RuntimeException.class, () ->
            getKms().unwrapDek(t1, wrapped, "datos", Map.of("doc", "d-2")));
    }

    @Test
    protected void kekDeOtroTenantFalla() {
        TenantId t1 = getTenantA();
        TenantId t2 = getTenantB();
        createKeyIfNeeded(t1, "datos");
        createKeyIfNeeded(t2, "datos");
        byte[] dek = new byte[32];
        Map<String, String> aad = Map.of("doc", "d-1");
        byte[] wrapped = getKms().wrapDek(t1, dek, "datos", aad).getData();
        assertThrows(RuntimeException.class, () -> 
            getKms().unwrapDek(t2, wrapped, "datos", aad));
    }

    @Test
    protected void disableKekImpideUnwrapInmediato() {
        TenantId t1 = getTenantA();
        createKeyIfNeeded(t1, "datos-disable");
        byte[] dek = new byte[32];
        Map<String, String> aad = Map.of("doc", "d-1");
        byte[] wrapped = getKms().wrapDek(t1, dek, "datos-disable", aad).getData();
        getKms().disableKek(t1, "datos-disable");
        assertThrows(KeyService.KeyDisabledException.class, () -> 
            getKms().unwrapDek(t1, wrapped, "datos-disable", aad));
    }

    @Test
    protected void tenantIdOKeyIdInvalidoFalla() {
        TenantId t1 = getTenantA();
        byte[] dek = new byte[32];
        for (String malo : new String[] {"../x", ".", "a:b", "", "x".repeat(65), null}) {
            assertThrows(IllegalArgumentException.class, () -> getKms().wrapDek(t1, dek, malo, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> getKms().unwrapDek(t1, dek, malo, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> getKms().sign(t1, dek, malo));
            assertThrows(IllegalArgumentException.class, () -> getKms().verify(t1, dek, dek, malo));
            assertThrows(IllegalArgumentException.class, () -> getKms().disableKek(t1, malo));
        }
        assertThrows(IllegalArgumentException.class, () -> getKms().wrapDek(new TenantId("t/1"), dek, "datos", Map.of()));
    }

    @Test
    protected void signYVerifyRoundtrip() {
        TenantId t1 = getTenantA();
        createKeyIfNeeded(t1, SIGNING_KEY);
        byte[] msg = "export".getBytes(StandardCharsets.UTF_8);
        byte[] sig = getKms().sign(t1, msg, SIGNING_KEY).getData();
        assertTrue(getKms().verify(t1, msg, sig, SIGNING_KEY));
        assertFalse(getKms().verify(t1, "otro".getBytes(StandardCharsets.UTF_8), sig, SIGNING_KEY));
    }

    @Test
    protected void signatureAlgorithmConsistenteConPublicKeys() {
        TenantId t1 = getTenantA();
        createKeyIfNeeded(t1, SIGNING_KEY);
        SignatureAlgorithm alg = getKms().signatureAlgorithm(t1, SIGNING_KEY);
        if (alg == SignatureAlgorithm.ED25519) {
            assertTrue(getKms().publicKeys(t1, SIGNING_KEY).isPresent());
        } else if (alg == SignatureAlgorithm.ES256) {
            assertFalse(getKms().publicKeys(t1, SIGNING_KEY).isPresent());
        }
    }

    @Test
    protected void providerFailureSePropagaComoUnavailable() {
        Optional<Runnable> fail = providerFailure();
        Assumptions.assumeTrue(fail.isPresent());
        fail.get().run();
        assertThrows(KeyServiceUnavailableException.class, () -> getKms().wrapDek(getTenantA(), new byte[32], "datos", Map.of()));
    }
}
