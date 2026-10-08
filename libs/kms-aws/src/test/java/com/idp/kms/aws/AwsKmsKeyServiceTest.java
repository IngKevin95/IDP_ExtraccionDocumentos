package com.idp.kms.aws;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.kms.Ed25519Verifier;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AwsKmsKeyServiceTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final TenantId T2 = new TenantId("t2");

    private final FakeAwsKmsApi api = new FakeAwsKmsApi();
    private final AwsKmsKeyService kms = new AwsKmsKeyService(api, 7);

    private void keys(TenantId t) {
        api.createKey(AwsKmsKeyService.alias(t, "datos"), false);
        api.createKey(AwsKmsKeyService.alias(t, "firma"), true);
    }

    @Test
    void aliasPorTenantYLlave() {
        assertEquals("alias/idp/t1/datos", AwsKmsKeyService.alias(T1, "datos"));
    }

    @Test
    void aadDistintoFalla() {
        keys(T1);
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", Map.of("doc", "d-1")).getData();
        assertThrows(KeyServiceUnavailableException.class,
            () -> kms.unwrapDek(T1, wrapped, "datos", Map.of("doc", "d-2")));
        assertThrows(KeyServiceUnavailableException.class, () -> kms.unwrapDek(T1, wrapped, "datos", Map.of()));
    }

    @Test
    void otroTenantUsaOtraLlave() {
        keys(T1);
        keys(T2);
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", Map.of("doc", "d-1")).getData();
        assertThrows(KeyServiceUnavailableException.class,
            () -> kms.unwrapDek(T2, wrapped, "datos", Map.of("doc", "d-1")));
    }

    @Test
    void llaveInexistenteEsKeyNotFound() {
        assertThrows(KeyService.KeyNotFoundException.class, () -> kms.wrapDek(T1, new byte[32], "datos", Map.of()));
    }

    @Test
    void disableKekBloqueaDeInmediatoYProgramaElBorradoConLaVentanaMinima() {
        keys(T1);
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", Map.of()).getData();
        kms.disableKek(T1, "datos");
        assertTrue(api.isDisabled("alias/idp/t1/datos"));
        assertEquals(7, api.deletionWindowDays("alias/idp/t1/datos"));
        assertThrows(KeyService.KeyDisabledException.class, () -> kms.unwrapDek(T1, wrapped, "datos", Map.of()));
        assertThrows(KeyService.KeyDisabledException.class, () -> kms.wrapDek(T1, new byte[32], "datos", Map.of()));
    }

    @Test
    void ventanaDeBorradoFueraDeRangoFalla() {
        for (int dias : new int[] {0, 6, 31}) {
            IllegalStateException e = assertThrows(IllegalStateException.class, () -> new AwsKmsKeyService(api, dias));
            assertTrue(e.getMessage().contains("entre 7 y 30"));
        }
        keys(T2);
        new AwsKmsKeyService(api, 30).disableKek(T2, "datos");
        assertEquals(30, api.deletionWindowDays("alias/idp/t2/datos"));
    }

    @Test
    void aliasNoColisionaEntreTenants() {
        TenantId ab = new TenantId("a-b");
        TenantId a = new TenantId("a");
        assertFalse(AwsKmsKeyService.alias(ab, "c").equals(AwsKmsKeyService.alias(a, "b-c")));
        api.createKey(AwsKmsKeyService.alias(ab, "c"), false);
        api.createKey(AwsKmsKeyService.alias(a, "b-c"), false);
        byte[] wrapped = kms.wrapDek(ab, new byte[32], "c", Map.of()).getData();
        assertThrows(KeyServiceUnavailableException.class, () -> kms.unwrapDek(a, wrapped, "b-c", Map.of()));
        kms.disableKek(a, "b-c");
        assertArrayEquals(new byte[32], kms.unwrapDek(ab, wrapped, "c", Map.of()).getData());
    }

    @Test
    void disableKekEsIdempotenteEntreInstancias() {
        keys(T1);
        kms.disableKek(T1, "datos");
        new AwsKmsKeyService(api, 7).disableKek(T1, "datos");
        kms.disableKek(T1, "datos");
    }

    @Test
    void disableKekDeLlaveInexistenteNoDejaElAliasBloqueado() {
        assertThrows(KeyService.KeyNotFoundException.class, () -> kms.disableKek(T1, "datos"));
        keys(T1);
        // Si el alias se hubiera quedado en el Set, aqui saldria KeyDisabledException.
        kms.wrapDek(T1, new byte[32], "datos", Map.of());
    }

    @Test
    void mensajeMayorAlLimiteRawSeRechazaSinLlamarAlProveedor() {
        keys(T1);
        api.calls.clear();
        byte[] grande = new byte[AwsKmsKeyService.MAX_RAW_MESSAGE_BYTES + 1];
        assertThrows(IllegalArgumentException.class, () -> kms.sign(T1, grande, "firma"));
        assertThrows(IllegalArgumentException.class, () -> kms.verify(T1, grande, new byte[64], "firma"));
        assertTrue(api.calls.isEmpty());
        byte[] limite = new byte[AwsKmsKeyService.MAX_RAW_MESSAGE_BYTES];
        assertTrue(kms.verify(T1, limite, kms.sign(T1, limite, "firma").getData(), "firma"));
    }

    @Test
    void disableKekBloqueaEnProcesoAunqueElProveedorFalle() {
        keys(T1);
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", Map.of()).getData();
        api.failDisable = true;
        assertThrows(KeyServiceUnavailableException.class, () -> kms.disableKek(T1, "datos"));
        api.failDisable = false;
        // El proveedor sigue viendo la llave habilitada, pero este proceso ya no la usa.
        assertFalse(api.isDisabled("alias/idp/t1/datos"));
        assertThrows(KeyService.KeyDisabledException.class, () -> kms.unwrapDek(T1, wrapped, "datos", Map.of()));
    }

    @Test
    void idsInvalidosNoLlamanAlProveedor() {
        keys(T1);
        api.calls.clear();
        for (String malo : new String[] {"../x", "a:b", "", "x".repeat(65), null}) {
            assertThrows(IllegalArgumentException.class, () -> kms.wrapDek(T1, new byte[32], malo, Map.of()));
            assertThrows(IllegalArgumentException.class, () -> kms.sign(T1, new byte[1], malo));
            assertThrows(IllegalArgumentException.class, () -> kms.publicKeys(T1, malo));
            assertThrows(IllegalArgumentException.class, () -> kms.signatureAlgorithm(T1, malo));
            assertThrows(IllegalArgumentException.class, () -> kms.disableKek(T1, malo));
        }
        assertThrows(IllegalArgumentException.class, () -> kms.wrapDek(new TenantId("t/1"), new byte[32], "datos", Map.of()));
        assertTrue(api.calls.isEmpty());
    }

    @Test
    void mensajesDeFalloSinDatosDelProveedor() {
        keys(T1);
        api.failing = true;
        KeyServiceUnavailableException e = assertThrows(KeyServiceUnavailableException.class,
            () -> kms.wrapDek(T1, new byte[32], "datos", Map.of("doc", "d-1")));
        assertEquals("KMS no disponible: IllegalStateException", e.getMessage());
        assertFalse(e.getMessage().contains("secreto-123"));
        assertEquals(null, e.getCause());
    }

    @Test
    void firmaYPublicKeysConsistentes() {
        keys(T1);
        assertEquals(SignatureAlgorithm.ED25519, kms.signatureAlgorithm(T1, "firma"));
        byte[] msg = "export".getBytes(StandardCharsets.UTF_8);
        byte[] sig = kms.sign(T1, msg, "firma").getData();
        assertTrue(kms.verify(T1, msg, sig, "firma"));
        Map<Integer, byte[]> pub = kms.publicKeys(T1, "firma").orElseThrow();
        assertEquals(32, pub.get(1).length);
        assertTrue(Ed25519Verifier.verify(pub, msg, sig));
        assertFalse(Ed25519Verifier.verify(pub, "otro".getBytes(StandardCharsets.UTF_8), sig));
    }

    @Test
    void publicKeyConFormatoInesperadoFalla() {
        AwsKmsKeyService raro = new AwsKmsKeyService(new FakeAwsKmsApi() {
            @Override
            public byte[] publicKey(String alias) {
                return new byte[44];
            }
        }, 7);
        assertThrows(KeyServiceUnavailableException.class, () -> raro.publicKeys(T1, "firma"));
    }

    @Test
    void unwrapDevuelveLaDekOriginal() {
        keys(T1);
        byte[] dek = new byte[32];
        dek[0] = 7;
        byte[] wrapped = kms.wrapDek(T1, dek, "datos", Map.of("doc", "d-1")).getData();
        assertArrayEquals(dek, kms.unwrapDek(T1, wrapped, "datos", Map.of("doc", "d-1")).getData());
    }
}
