package com.idp.kms;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;

class InMemoryKeyServiceTest {

    private final InMemoryKeyService kms = new InMemoryKeyService();
    private final TenantId t1 = new TenantId("t1");
    private final TenantId t2 = new TenantId("t2");
    private final Map<String, String> aad = Map.of("tenant", "t1", "doc", "d-1");

    @Test
    void wrapYUnwrapDevuelvenLaDekOriginal() {
        byte[] dek = AesGcm.newKey();
        byte[] wrapped = kms.wrapDek(t1, dek, "datos", aad).getData();
        assertFalse(Arrays.equals(dek, wrapped));
        assertArrayEquals(dek, kms.unwrapDek(t1, wrapped, "datos", aad).getData());
    }

    @Test
    void unwrapConAadDistintoFalla() {
        byte[] wrapped = kms.wrapDek(t1, AesGcm.newKey(), "datos", aad).getData();
        assertThrows(IllegalArgumentException.class,
            () -> kms.unwrapDek(t1, wrapped, "datos", Map.of("tenant", "t1", "doc", "otro")));
    }

    @Test
    void kekDeOtroTenantNoDescifra() {
        byte[] wrapped = kms.wrapDek(t1, AesGcm.newKey(), "datos", aad).getData();
        assertThrows(KeyService.KeyNotFoundException.class, () -> kms.unwrapDek(t2, wrapped, "datos", aad));
    }

    @Test
    void ac04_disableKekImpideWrapYUnwrapDeInmediato() {
        byte[] wrapped = kms.wrapDek(t1, AesGcm.newKey(), "datos", aad).getData();
        kms.disableKek(t1, "datos");
        assertThrows(KeyService.KeyDisabledException.class, () -> kms.unwrapDek(t1, wrapped, "datos", aad));
        assertThrows(KeyService.KeyDisabledException.class, () -> kms.wrapDek(t1, new byte[32], "datos", aad));
    }

    @Test
    void ac04_disableKekDeDatosNoAfectaLaKekDeAuditoria() {
        byte[] wrappedAudit = kms.wrapDek(t1, AesGcm.newKey(), "auditoria", aad).getData();
        kms.disableKek(t1, "datos");
        assertEquals(32, kms.unwrapDek(t1, wrappedAudit, "auditoria", aad).getData().length);
    }

    @Test
    void ac07_firmaYVerificaConLlaveDeFirmaPropia() {
        byte[] msg = "export".getBytes(StandardCharsets.UTF_8);
        byte[] sig = kms.sign(t1, msg, "firma").getData();
        assertTrue(kms.verify(t1, msg, sig, "firma"));
        assertFalse(kms.verify(t1, "otro".getBytes(StandardCharsets.UTF_8), sig, "firma"));
        assertNotEquals(0, sig.length);
    }

    @Test
    void firmaDeshabilitadaSeRechaza() {
        kms.sign(t1, new byte[] {1}, "firma");
        kms.disableKek(t1, "firma");
        assertThrows(KeyService.KeyDisabledException.class, () -> kms.sign(t1, new byte[] {1}, "firma"));
    }
}
