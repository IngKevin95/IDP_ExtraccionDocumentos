package com.idp.kms.gcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.kms.Ed25519Verifier;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.kms.SignatureAlgorithm;
import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class GcpKmsKeyServiceTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final TenantId T2 = new TenantId("t2");
    private static final byte[] MSG = "export".getBytes(StandardCharsets.UTF_8);

    private final FakeGcpKmsApi fake = new FakeGcpKmsApi();
    private final AtomicLong clock = new AtomicLong();
    private final GcpKmsKeyService kms = new GcpKmsKeyService(fake, "p", "l", "r", clock::get);

    private String key(TenantId t, String id) {
        return GcpKmsContractTest.cryptoKey(t, id);
    }

    @Test
    void cryptoKeyEsHasheadaDe63CaracteresYNoRevelaTenantNiId() {
        fake.createEncryptionKey(key(T1, "datos-secretos"));
        kms.wrapDek(T1, new byte[32], "datos-secretos", Map.of());

        String name = fake.calls.get(0);
        String id = name.substring(name.lastIndexOf('/') + 1);
        assertThat(name).startsWith("projects/p/locations/l/keyRings/r/cryptoKeys/idp-");
        assertThat(id).hasSize(63).matches("idp-[0-9a-f]{59}")
            .isEqualTo(KeyNames.hashed(T1, "datos-secretos").substring(0, 63));
        assertThat(name).doesNotContain("t1").doesNotContain("datos-secretos");
    }

    @Test
    void cadaTenantUsaOtraCryptoKey() {
        fake.createEncryptionKey(key(T1, "datos"));
        fake.createEncryptionKey(key(T2, "datos"));
        kms.wrapDek(T1, new byte[32], "datos", Map.of());
        kms.wrapDek(T2, new byte[32], "datos", Map.of());
        assertThat(fake.calls.get(0)).isNotEqualTo(fake.calls.get(1));
    }

    @Test
    void llaveInexistenteEsKeyNotFoundYNoUnavailable() {
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "no-aprovisionada", Map.of()))
            .isInstanceOf(KeyService.KeyNotFoundException.class);
    }

    @Test
    void disableKekEsInmediatoAunqueElProveedorFalle() {
        fake.createEncryptionKey(key(T1, "datos"));
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", Map.of()).getData();
        fake.failure = new IllegalStateException("UNAVAILABLE");

        assertThatThrownBy(() -> kms.disableKek(T1, "datos")).isInstanceOf(KeyServiceUnavailableException.class);

        fake.failure = null;
        int llamadas = fake.calls.size();
        assertThatThrownBy(() -> kms.unwrapDek(T1, wrapped, "datos", Map.of()))
            .isInstanceOf(KeyService.KeyDisabledException.class);
        assertThat(fake.calls).hasSize(llamadas);
    }

    @Test
    void disableKekProgramaLaDestruccionYDeshabilitaTambienLaFirma() {
        fake.createEncryptionKey(key(T1, "datos"));
        kms.disableKek(T1, "datos");
        assertThat(fake.destroyScheduled(key(T1, "datos"))).isTrue();
        assertThatThrownBy(() -> kms.sign(T1, MSG, "datos")).isInstanceOf(KeyService.KeyDisabledException.class);
        assertThatThrownBy(() -> kms.verify(T1, MSG, MSG, "datos")).isInstanceOf(KeyService.KeyDisabledException.class);
    }

    @Test
    void idsInvalidosNoLlamanAlProveedor() {
        byte[] dek = new byte[32];
        for (String malo : new String[] {"../x", "a:b", "", "x".repeat(65), null}) {
            assertThatThrownBy(() -> kms.wrapDek(T1, dek, malo, Map.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> kms.unwrapDek(T1, dek, malo, Map.of())).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> kms.sign(T1, dek, malo)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> kms.verify(T1, dek, dek, malo)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> kms.publicKeys(T1, malo)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> kms.signatureAlgorithm(T1, malo)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> kms.disableKek(T1, malo)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> kms.wrapDek(new TenantId("t/1"), dek, "datos", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(fake.calls).isEmpty();
    }

    @Test
    void elMensajeDeUnavailableNoFiltraElErrorDelProveedor() {
        fake.createEncryptionKey(key(T1, "datos"));
        fake.failure = new IllegalStateException("token=ya29.SECRETO cuenta alice@banco.com projects/p/keys/datos");

        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", Map.of()))
            .isInstanceOf(KeyServiceUnavailableException.class)
            .hasMessageContaining("IllegalStateException")
            .hasMessageNotContaining("SECRETO").hasMessageNotContaining("alice").hasMessageNotContaining("datos")
            .hasNoCause();
    }

    @Test
    void verificaLocalmenteConLaLlavePublicaYLaCachea() {
        fake.createSigningKey(key(T1, "firma"));
        byte[] sig = kms.sign(T1, MSG, "firma").getData();
        int llamadas = fake.calls.size();

        assertThat(kms.verify(T1, MSG, sig, "firma")).isTrue();
        assertThat(kms.verify(T1, "otro".getBytes(StandardCharsets.UTF_8), sig, "firma")).isFalse();
        assertThat(fake.calls).hasSize(llamadas + 1);

        Map<Integer, byte[]> publicKeys = kms.publicKeys(T1, "firma").orElseThrow();
        assertThat(publicKeys).containsOnlyKeys(1);
        assertThat(publicKeys.get(1)).hasSize(32);
        assertThat(Ed25519Verifier.verify(publicKeys, MSG, sig)).isTrue();
    }

    @Test
    void laFirmaDeOtroTenantNoVerifica() {
        fake.createSigningKey(key(T1, "firma"));
        fake.createSigningKey(key(T2, "firma"));
        byte[] sig = kms.sign(T1, MSG, "firma").getData();
        assertThat(kms.verify(T2, MSG, sig, "firma")).isFalse();
    }

    @Test
    void signatureAlgorithmEsEd25519() {
        assertThat(kms.signatureAlgorithm(T1, "firma")).isEqualTo(SignatureAlgorithm.ED25519);
    }

    @Test
    void laLlavePublicaCacheadaExpiraYReflejaLaRotacion() {
        String name = key(T1, "firma");
        fake.createSigningKey(name);
        assertThat(kms.publicKeys(T1, "firma").orElseThrow()).containsOnlyKeys(1);

        fake.rotateSigningKey(name);
        assertThat(kms.publicKeys(T1, "firma").orElseThrow()).containsOnlyKeys(1);

        clock.addAndGet(TimeUnit.MINUTES.toNanos(6));
        assertThat(kms.publicKeys(T1, "firma").orElseThrow()).containsOnlyKeys(1, 2);
        byte[] sig = kms.sign(T1, MSG, "firma").getData();
        assertThat(kms.verify(T1, MSG, sig, "firma")).isTrue();
    }

    @Test
    void unaLlavePublicaQueNoEsEd25519EsUnavailable() {
        GcpKmsApi api = new FakeGcpKmsApi() {
            @Override
            public Map<Integer, byte[]> publicKeys(String cryptoKey) {
                return Map.of(1, new byte[10]);
            }
        };
        GcpKmsKeyService service = new GcpKmsKeyService(api, "p", "l", "r");
        assertThatThrownBy(() -> service.publicKeys(T1, "firma")).isInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void laFirmaSaleEtiquetadaConLaVersionQueFirmo() {
        fake.createSigningKey(key(T1, "firma"));
        fake.rotateSigningKey(key(T1, "firma"));
        String sig = new String(kms.sign(T1, MSG, "firma").getData(), StandardCharsets.UTF_8);
        assertThat(sig).startsWith("vault:v2:");
    }

    @Test
    void lasFirmasViejasYNuevasVerificanTrasRotarSinEsperar() {
        String name = key(T1, "firma");
        fake.createSigningKey(name);
        kms.publicKeys(T1, "firma");
        byte[] vieja = kms.sign(T1, MSG, "firma").getData();
        fake.rotateSigningKey(name);
        byte[] nueva = kms.sign(T1, MSG, "firma").getData();

        assertThat(new String(nueva, StandardCharsets.UTF_8)).startsWith("vault:v2:");
        assertThat(kms.verify(T1, MSG, nueva, "firma")).isTrue();
        assertThat(kms.verify(T1, MSG, vieja, "firma")).isTrue();
        assertThat(kms.publicKeys(T1, "firma").orElseThrow()).containsOnlyKeys(1, 2);

        String payload = new String(vieja, StandardCharsets.UTF_8).substring("vault:v1:".length());
        byte[] cruda = java.util.Base64.getDecoder().decode(payload);
        assertThat(kms.verify(T1, MSG, cruda, "firma")).isTrue();
    }

    @Test
    void unaFirmaDeOtraInstanciaTrasRotarVerificaPorElRefrescoUnico() {
        String name = key(T1, "firma");
        fake.createSigningKey(name);
        kms.publicKeys(T1, "firma");
        fake.rotateSigningKey(name);
        byte[] deOtro = new GcpKmsKeyService(fake, "p", "l", "r").sign(T1, MSG, "firma").getData();

        assertThat(kms.verify(T1, MSG, deOtro, "firma")).isFalse();
        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThat(kms.verify(T1, MSG, deOtro, "firma")).isTrue();
    }

    @Test
    void unVerifyFallidoRefrescaComoMuchoUnaVezPorVentana() {
        fake.createSigningKey(key(T1, "firma"));
        kms.publicKeys(T1, "firma");
        byte[] falsa = "vault:v9:AAAA".getBytes(StandardCharsets.UTF_8);
        int llamadas = fake.calls.size();

        assertThat(kms.verify(T1, MSG, falsa, "firma")).isFalse();
        assertThat(fake.calls).hasSize(llamadas);

        clock.addAndGet(TimeUnit.SECONDS.toNanos(31));
        assertThat(kms.verify(T1, MSG, falsa, "firma")).isFalse();
        assertThat(fake.calls).hasSize(llamadas + 1);
        assertThat(kms.verify(T1, MSG, falsa, "firma")).isFalse();
        assertThat(fake.calls).hasSize(llamadas + 1);
    }

    @Test
    void signRechazaDatosDeMasDe64KiBAntesDeLlamarAlProveedor() {
        fake.createSigningKey(key(T1, "firma"));
        assertThatThrownBy(() -> kms.sign(T1, new byte[64 * 1024 + 1], "firma"))
            .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("65536");
        assertThat(fake.calls).isEmpty();
        assertThat(kms.sign(T1, new byte[64 * 1024], "firma").getData()).isNotEmpty();
    }

    @Test
    void unwrapConAadDistintoEsArgumentoInvalidoYNoUnavailable() {
        fake.createEncryptionKey(key(T1, "datos"));
        byte[] wrapped = kms.wrapDek(T1, new byte[32], "datos", Map.of("doc", "1")).getData();
        assertThatThrownBy(() -> kms.unwrapDek(T1, wrapped, "datos", Map.of("doc", "2")))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
