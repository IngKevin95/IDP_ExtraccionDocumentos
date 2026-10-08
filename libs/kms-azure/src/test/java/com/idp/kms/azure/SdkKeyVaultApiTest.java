package com.idp.kms.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.http.HttpResponse;
import com.azure.core.util.polling.SyncPoller;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.SignResult;
import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.UnwrapResult;
import com.azure.security.keyvault.keys.cryptography.models.VerifyResult;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;
import com.azure.security.keyvault.keys.models.DeletedKey;
import com.azure.security.keyvault.keys.models.KeyProperties;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import com.idp.kms.AadContext;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.kms.KeyServiceUnavailableException;
import com.idp.tenant.TenantId;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Lo que el adaptador real envia al SDK (nombre, version, algoritmo, material) y como traduce sus errores. */
class SdkKeyVaultApiTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final String NOMBRE = KeyNames.hashed(T1, "datos");
    private static final String KID = "https://v.vault.azure.net/keys/" + NOMBRE + "/abc123";
    private static final Map<String, String> AAD = Map.of("doc", "d-1");

    private final KeyClient keyClient = mock(KeyClient.class);
    private final CryptographyClient crypto = mock(CryptographyClient.class);
    /** "nombre@version" (version null = vigente) por cada cliente criptografico creado. */
    private final List<String> creados = new ArrayList<>();
    private final long[] reloj = {0};
    private final AzureKeyVaultKeyService kms = new AzureKeyVaultKeyService(new SdkKeyVaultApi(keyClient,
        (nombre, version) -> {
            creados.add(nombre + "@" + version);
            return crypto;
        }, () -> reloj[0], 1000, true));

    private static byte[] hashAad(Map<String, String> aad) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(AadContext.canonical(aad));
    }

    private static HttpResponseException http(int status, String mensaje) {
        // Respuesta con answer por defecto: crearla no abre un stubbing (se usa dentro de thenThrow).
        HttpResponse resp = mock(HttpResponse.class,
            inv -> "getStatusCode".equals(inv.getMethod().getName()) ? Integer.valueOf(status) : null);
        return new HttpResponseException(mensaje, resp);
    }

    private byte[] wrapSimulado(byte[] ciphertext) {
        when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class)))
            .thenReturn(new WrapResult(ciphertext, KeyWrapAlgorithm.RSA_OAEP_256, KID));
        return kms.wrapDek(T1, new byte[32], "datos", AAD).getData();
    }

    @Test
    void wrapEnviaRsaOaep256SobreDekMasHashDelAadYGuardaLaVersionEnElBlob() throws Exception {
        byte[] dek = new byte[32];
        Arrays.fill(dek, (byte) 9);
        byte[][] visto = new byte[1][];
        when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class))).thenAnswer(inv -> {
            visto[0] = ((byte[]) inv.getArgument(1)).clone();
            return new WrapResult(new byte[] {7, 8}, KeyWrapAlgorithm.RSA_OAEP_256, KID);
        });

        byte[] blob = kms.wrapDek(T1, dek, "datos", AAD).getData();

        byte[] esperado = Arrays.copyOf(dek, 64);
        System.arraycopy(hashAad(AAD), 0, esperado, 32, 32);
        assertThat(visto[0]).isEqualTo(esperado);
        verify(crypto).wrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class));
        assertThat(creados).containsExactly(NOMBRE + "@null");
        // 0x01 | 6 | "abc123" | ciphertext
        assertThat(blob).containsExactly(1, 6, 'a', 'b', 'c', '1', '2', '3', 7, 8);
    }

    @Test
    void unwrapUsaLaVersionDelBlobYSeparaElSufijo() throws Exception {
        byte[] dek = {5, 6, 7};
        byte[] blob = wrapSimulado(new byte[] {7, 8});
        byte[] plano = Arrays.copyOf(dek, 3 + 32);
        System.arraycopy(hashAad(AAD), 0, plano, 3, 32);
        ArgumentCaptor<byte[]> enviado = ArgumentCaptor.forClass(byte[].class);
        when(crypto.unwrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), enviado.capture()))
            .thenReturn(new UnwrapResult(plano.clone(), KeyWrapAlgorithm.RSA_OAEP_256, KID));

        assertThat(kms.unwrapDek(T1, blob, "datos", AAD).getData()).isEqualTo(dek);

        assertThat(enviado.getValue()).containsExactly(7, 8);
        assertThat(creados).contains(NOMBRE + "@abc123");
        when(crypto.unwrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class)))
            .thenReturn(new UnwrapResult(plano.clone(), KeyWrapAlgorithm.RSA_OAEP_256, KID));
        assertThatThrownBy(() -> kms.unwrapDek(T1, blob, "datos", Map.of("doc", "otro")))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("No se pudo descifrar");
    }

    @Test
    void elClienteVigenteSeRenuevaPorTtlYElDeVersionSeConserva() {
        when(crypto.signData(any(SignatureAlgorithm.class), any(byte[].class)))
            .thenReturn(new SignResult(new byte[64], SignatureAlgorithm.ES256, KID));
        kms.sign(T1, new byte[1], "datos");
        reloj[0] = 999;
        kms.sign(T1, new byte[1], "datos");
        assertThat(creados).containsExactly(NOMBRE + "@null");
        reloj[0] = 1000;
        kms.sign(T1, new byte[1], "datos");
        assertThat(creados).containsExactly(NOMBRE + "@null", NOMBRE + "@null");

        byte[] blob = wrapSimulado(new byte[] {1});
        when(crypto.unwrapKey(any(KeyWrapAlgorithm.class), any(byte[].class)))
            .thenReturn(new UnwrapResult(new byte[40], KeyWrapAlgorithm.RSA_OAEP_256, KID));
        for (int i = 0; i < 2; i++) {
            assertThatThrownBy(() -> kms.unwrapDek(T1, blob, "datos", AAD)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(creados.stream().filter(c -> c.endsWith("@abc123"))).hasSize(1);
    }

    @Test
    void signYVerifyUsanEs256() {
        byte[] sig = new byte[64];
        when(crypto.signData(eq(SignatureAlgorithm.ES256), any(byte[].class)))
            .thenReturn(new SignResult(sig, SignatureAlgorithm.ES256, KID));
        when(crypto.verifyData(eq(SignatureAlgorithm.ES256), any(byte[].class), any(byte[].class)))
            .thenReturn(new VerifyResult(true, SignatureAlgorithm.ES256, KID));

        assertThat(kms.sign(T1, new byte[] {1}, "datos").getData()).hasSize(64);
        assertThat(kms.verify(T1, new byte[] {1}, sig, "datos")).isTrue();
        verify(crypto).signData(eq(SignatureAlgorithm.ES256), eq(new byte[] {1}));
    }

    @Test
    void verifyConFirmaDeLargoInvalidoNoLlamaAlSdk() {
        assertThat(kms.verify(T1, new byte[] {1}, new byte[10], "datos")).isFalse();
        assertThat(kms.verify(T1, new byte[] {1}, null, "datos")).isFalse();
        verify(crypto, never()).verifyData(any(SignatureAlgorithm.class), any(byte[].class), any(byte[].class));
        assertThat(creados).isEmpty();
    }

    @SuppressWarnings("unchecked")
    private SyncPoller<DeletedKey, Void> keyExiste() {
        KeyVaultKey key = mock(KeyVaultKey.class);
        when(key.getProperties()).thenReturn(new KeyProperties());
        when(keyClient.getKey(NOMBRE)).thenReturn(key);
        SyncPoller<DeletedKey, Void> poller = mock(SyncPoller.class);
        when(keyClient.beginDeleteKey(NOMBRE)).thenReturn(poller);
        return poller;
    }

    @Test
    void disableKekDeshabilitaEliminaYEsperaElBorrado() {
        SyncPoller<DeletedKey, Void> poller = keyExiste();

        kms.disableKek(T1, "datos");

        ArgumentCaptor<KeyProperties> updated = ArgumentCaptor.forClass(KeyProperties.class);
        verify(keyClient).updateKeyProperties(updated.capture());
        assertThat(updated.getValue().isEnabled()).isFalse();
        verify(keyClient).beginDeleteKey(NOMBRE);
        verify(poller).waitForCompletion(any(Duration.class));
    }

    @Test
    void disableKekEsIdempotenteSiLaLlaveYaNoExiste() {
        when(keyClient.getKey(NOMBRE)).thenThrow(http(404, "no existe"));
        kms.disableKek(T1, "datos");
        verify(keyClient, never()).beginDeleteKey(any(String.class));
    }

    @Test
    void disableKekConvergeSiElBorradoYaFueAplicadoPorOtraReplica() {
        keyExiste();
        when(keyClient.beginDeleteKey(NOMBRE)).thenThrow(http(409, "Key is currently being deleted"));
        kms.disableKek(T1, "datos");
    }

    @Test
    void siElBorradoNoCompletaSeReportaUnavailable() {
        SyncPoller<DeletedKey, Void> poller = keyExiste();
        when(poller.waitForCompletion(any(Duration.class))).thenThrow(new IllegalStateException("timeout"));
        assertThatThrownBy(() -> kms.disableKek(T1, "datos")).isInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void siNoSePuedeDeshabilitarNoSeEliminaYSigueDeshabilitadoEnProceso() {
        when(keyClient.getKey(NOMBRE)).thenThrow(http(503, "no disponible"));
        assertThatThrownBy(() -> kms.disableKek(T1, "datos"))
            .isInstanceOf(KeyServiceUnavailableException.class)
            .hasMessage("KMS no disponible: HttpResponseException").hasNoCause();
        verify(keyClient, never()).beginDeleteKey(any(String.class));
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", Map.of()))
            .isInstanceOf(KeyService.KeyDisabledException.class);
        verify(crypto, never()).wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class));
    }

    @Test
    void http404SeTraduceAKeyNotFound() {
        when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class))).thenThrow(http(404, "no existe"));
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", Map.of()))
            .isInstanceOf(KeyService.KeyNotFoundException.class);
    }

    @Test
    void http403Y409PorLlaveDeshabilitadaOEliminadaSonKeyDisabled() {
        for (HttpResponseException e : new HttpResponseException[] {
            http(403, "Operation wrapKey is not permitted on a disabled key"),
            http(409, "Key is currently in a deleted but recoverable state (ObjectIsDeletedButRecoverable)")}) {
            when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class))).thenThrow(e);
            assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", Map.of()))
                .isInstanceOf(KeyService.KeyDisabledException.class);
        }
    }

    @Test
    void http403DeConfiguracion429Y5xxSonUnavailable() {
        for (int status : new int[] {401, 403, 429, 500, 503}) {
            when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class))).thenThrow(http(status, "Forbidden"));
            assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", Map.of()))
                .isInstanceOf(KeyServiceUnavailableException.class).hasNoCause();
        }
    }

    @Test
    void http400EnUnwrapEsElErrorGenericoDeDescifradoPeroEnWrapEsUnavailable() {
        byte[] blob = wrapSimulado(new byte[] {1});
        when(crypto.unwrapKey(any(KeyWrapAlgorithm.class), any(byte[].class))).thenThrow(http(400, "BadParameter"));
        assertThatThrownBy(() -> kms.unwrapDek(T1, blob, "datos", AAD))
            .isInstanceOf(IllegalArgumentException.class).hasMessage("No se pudo descifrar").hasNoCause();

        when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class))).thenThrow(http(400, "BadParameter"));
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", AAD))
            .isInstanceOf(KeyServiceUnavailableException.class);
    }

    @Test
    void unwrapSinVersionDeLlaveEnLaRespuestaDeWrapEsUnavailable() {
        when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class)))
            .thenReturn(new WrapResult(new byte[] {1}, KeyWrapAlgorithm.RSA_OAEP_256, null));
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", AAD))
            .isInstanceOf(KeyServiceUnavailableException.class);
    }
}
