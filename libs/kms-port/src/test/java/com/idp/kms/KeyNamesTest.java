package com.idp.kms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.idp.tenant.TenantId;
import org.junit.jupiter.api.Test;

class KeyNamesTest {

    @Test
    void nombreDeterministaYApto() {
        String n = KeyNames.hashed(new TenantId("t1"), "datos");
        assertEquals(n, KeyNames.hashed(new TenantId("t1"), "datos"));
        assertEquals(68, n.length());
        assertTrue(n.matches("idp-[a-f0-9]{64}"));
    }

    @Test
    void noColisionaPorTransliteracionNiPorFronteraDeCampos() {
        assertNotEquals(KeyNames.hashed(new TenantId("a_b"), "k"), KeyNames.hashed(new TenantId("a-b"), "k"));
        assertNotEquals(KeyNames.hashed(new TenantId("ab"), "c"), KeyNames.hashed(new TenantId("a"), "bc"));
        assertNotEquals(KeyNames.hashed(new TenantId("t1"), "datos"), KeyNames.hashed(new TenantId("t2"), "datos"));
    }

    @Test
    void rechazaIdentificadoresInvalidos() {
        for (String malo : new String[] {"../x", ".", "a:b", "", "x".repeat(65), null}) {
            assertThrows(IllegalArgumentException.class, () -> KeyNames.hashed(new TenantId("t1"), malo));
        }
        assertThrows(IllegalArgumentException.class, () -> KeyNames.hashed(new TenantId("t/1"), "datos"));
    }
}
