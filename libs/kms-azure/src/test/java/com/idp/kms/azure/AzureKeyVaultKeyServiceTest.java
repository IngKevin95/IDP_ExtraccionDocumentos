package com.idp.kms.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.kms.AadContext;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Multicloud AC-11 a AC-14 y AC-16 y SEC-055/SEC-056 especificos de Key Vault. */
class AzureKeyVaultKeyServiceTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final TenantId T2 = new TenantId("t2");
    private static final Map<String, String> AAD = Map.of("doc", "d-1");

    private final FakeKeyVaultApi fake = new FakeKeyVaultApi();
    private final AzureKeyVaultKeyService kms = new AzureKeyVaultKeyService(fake);

    @BeforeEach
    void llaves() {
        fake.provisionRsa(KeyNames.hashed(T1, "datos"));
        fake.provisionRsa(KeyNames.hashed(T2, "datos"));
        fake.provisionEc(KeyNames.hashed(T1, "firma"));
    }

    private static byte[] sha256(byte[] in) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(in);
    }

    @Test
    void ac12MaterialEnviadoAWrapEsDekMasSha256DelAadCanonico() throws Exception {
        byte[] dek = new byte[32];
        Arrays.fill(dek, (byte) 7);
        kms.wrapDek(T1, dek, "datos", AAD);

        byte[] hash = sha256(AadContext.canonical(AAD));
        byte[] esperado = Arrays.copyOf(dek, 64);
        System.arraycopy(hash, 0, esperado, 32, 32);
        assertThat(fake.lastWrapMaterial).isEqualTo(esperado);
        assertThat(fake.calls).containsExactly(KeyNames.hashed(T1, "datos"));
    }

    @Test
    void ac12AadAlteradoFallaEnLaComparacionDelSufijoYNoDevuelveLaDek() {
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", AAD).getData();
        assertThatThrownBy(() -> kms.unwrapDek(T1, wrapped, "datos", Map.of("doc", "d-2")))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("AAD");
        assertThatThrownBy(() -> kms.unwrapDek(T1, wrapped, "datos", null))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aadVacioHaceIdaYVuelta() {
        byte[] dek = {1, 2, 3, 4};
        byte[] wrapped = kms.wrapDek(T1, dek, "datos", Map.of()).getData();
        assertThat(kms.unwrapDek(T1, wrapped, "datos", null).getData()).isEqualTo(dek);
    }

    @Test
    void materialDescifradoMasCortoQueElSufijoFalla() {
        // Se envuelve directo en el proveedor algo de menos de 32 bytes: unwrap lo rechaza.
        byte[] corto = fake.wrap(KeyNames.hashed(T1, "datos"), new byte[10]);
        assertThatThrownBy(() -> kms.unwrapDek(T1, corto, "datos", AAD))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blobEnvueltoTruncadoFalla() {
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", AAD).getData();
        byte[] truncado = Arrays.copyOf(wrapped, wrapped.length - 5);
        assertThatThrownBy(() -> kms.unwrapDek(T1, truncado, "datos", AAD)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void otroTenantUsaOtraLlaveYFalla() {
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", AAD).getData();
        assertThatThrownBy(() -> kms.unwrapDek(T2, wrapped, "datos", AAD)).isInstanceOf(RuntimeException.class);
        assertThat(fake.calls).contains(KeyNames.hashed(T2, "datos"));
    }

    @Test
    void llaveInexistenteSeReportaComoKeyNotFound() {
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "otra", AAD))
            .isInstanceOf(KeyService.KeyNotFoundException.class);
    }

    @Test
    void ac11DisableKekEsInmediatoYEliminaLaLlaveEnElProveedor() {
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", AAD).getData();
        kms.disableKek(T1, "datos");
        fake.calls.clear();
        assertThatThrownBy(() -> kms.unwrapDek(T1, wrapped, "datos", AAD))
            .isInstanceOf(KeyService.KeyDisabledException.class);
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", AAD))
            .isInstanceOf(KeyService.KeyDisabledException.class);
        assertThat(fake.calls).isEmpty();
        // Un segundo desactivado es un fallo del proveedor (la llave ya no existe), pero sigue deshabilitada.
        assertThatThrownBy(() -> kms.disableKek(T1, "datos")).isInstanceOf(KeyService.KeyNotFoundException.class);
    }

    @Test
    void ac11DisableKekDeshabilitaEnProcesoAunqueElProveedorFalle() {
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", AAD).getData();
        fake.failure = new IllegalStateException("caida simulada");
        assertThatThrownBy(() -> kms.disableKek(T1, "datos")).isInstanceOf(KeyServiceUnavailableException.class);
        fake.failure = null;
        assertThatThrownBy(() -> kms.unwrapDek(T1, wrapped, "datos", AAD))
            .isInstanceOf(KeyService.KeyDisabledException.class);
    }

    @Test
    void ac13FalloDelProveedorEsUnavailableSinPiiNiMensajeDelSdk() {
        fake.failure = new IllegalStateException("cuenta 123456 token abc");
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", AAD))
            .isInstanceOf(KeyServiceUnavailableException.class)
            .hasMessage("KMS no disponible: IllegalStateException");
        assertThatThrownBy(() -> kms.sign(T1, new byte[3], "firma"))
            .isInstanceOf(KeyServiceUnavailableException.class)
            .hasMessageNotContaining("123456").hasMessageNotContaining("token");
        assertThatThrownBy(() -> kms.verify(T1, new byte[3], new byte[64], "firma"))
            .isInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void ac14IdsInvalidosNoLlamanAlProveedor() {
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "../x", AAD))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kms.unwrapDek(new TenantId("t/1"), new byte[64], "datos", AAD))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kms.sign(T1, new byte[3], null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kms.disableKek(T1, "a:b")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kms.signatureAlgorithm(T1, "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> kms.publicKeys(T1, "x".repeat(65))).isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void ac16Es256SinLlavesPublicasLocales() {
        assertThat(kms.signatureAlgorithm(T1, "firma")).isEqualTo(SignatureAlgorithm.ES256);
        assertThat(kms.publicKeys(T1, "firma")).isEmpty();
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void ac16FirmaDe64BytesRsCrudaVerificableConElProveedor() throws Exception {
        byte[] msg = "export".getBytes(StandardCharsets.UTF_8);
        byte[] sig = kms.sign(T1, msg, "firma").getData();
        assertThat(sig).hasSize(64);
        assertThat(kms.verify(T1, msg, sig, "firma")).isTrue();
        byte[] mala = sig.clone();
        mala[0] ^= 1;
        assertThat(kms.verify(T1, msg, mala, "firma")).isFalse();
    }

    @Test
    void firmaSinLongitudEsperadaDelProveedorEsUnavailable() {
        KeyVaultApi raro = new FakeKeyVaultApi() {
            @Override
            public byte[] sign(String keyName, byte[] data) {
                return new byte[10];
            }
        };
        assertThatThrownBy(() -> new AzureKeyVaultKeyService(raro).sign(T1, new byte[1], "firma"))
            .isInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void nombreDeLlaveEsElHashDelParTenantKeyId() {
        String nombre = KeyNames.hashed(T1, "datos");
        assertThat(nombre).matches("idp-[0-9a-f]{59}").doesNotContain("_");
        assertThat(nombre).isNotEqualTo(KeyNames.hashed(T2, "datos"));
        kms.wrapDek(T1, new byte[32], "datos", AAD);
        assertThat(fake.calls).containsExactly(nombre);
    }
}
