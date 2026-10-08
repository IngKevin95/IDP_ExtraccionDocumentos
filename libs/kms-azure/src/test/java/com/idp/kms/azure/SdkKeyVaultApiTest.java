package com.idp.kms.azure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.azure.core.exception.HttpResponseException;
import com.azure.core.exception.ResourceNotFoundException;
import com.azure.core.http.HttpResponse;
import com.azure.security.keyvault.keys.KeyClient;
import com.azure.security.keyvault.keys.cryptography.CryptographyClient;
import com.azure.security.keyvault.keys.cryptography.models.KeyWrapAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.SignResult;
import com.azure.security.keyvault.keys.cryptography.models.SignatureAlgorithm;
import com.azure.security.keyvault.keys.cryptography.models.UnwrapResult;
import com.azure.security.keyvault.keys.cryptography.models.VerifyResult;
import com.azure.security.keyvault.keys.cryptography.models.WrapResult;
import com.azure.security.keyvault.keys.models.KeyProperties;
import com.azure.security.keyvault.keys.models.KeyVaultKey;
import com.idp.kms.AadContext;
import com.idp.kms.KeyNames;
import com.idp.kms.KeyService;
import com.idp.tenant.TenantId;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Lo que el adaptador real envia al SDK: nombre de llave, algoritmos y material. */
class SdkKeyVaultApiTest {

    private static final TenantId T1 = new TenantId("t1");
    private static final String NOMBRE = KeyNames.hashed(T1, "datos");

    private final KeyClient keyClient = mock(KeyClient.class);
    private final CryptographyClient crypto = mock(CryptographyClient.class);
    private final List<String> nombresPedidos = new ArrayList<>();
    private final AzureKeyVaultKeyService kms = new AzureKeyVaultKeyService(
        new SdkKeyVaultApi(keyClient, nombre -> {
            nombresPedidos.add(nombre);
            return crypto;
        }));

    @Test
    void wrapEnviaRsaOaep256SobreDekMasHashDelAadYUsaLaLlaveHasheada() throws Exception {
        byte[] dek = new byte[32];
        Arrays.fill(dek, (byte) 9);
        Map<String, String> aad = Map.of("doc", "d-1");
        byte[][] visto = new byte[1][];
        when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class))).thenAnswer(inv -> {
            visto[0] = ((byte[]) inv.getArgument(1)).clone();
            return new WrapResult(new byte[] {1}, KeyWrapAlgorithm.RSA_OAEP_256, "kid");
        });

        kms.wrapDek(T1, dek, "datos", aad);

        byte[] esperado = Arrays.copyOf(dek, 64);
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(AadContext.canonical(aad)), 0, esperado, 32, 32);
        assertThat(visto[0]).isEqualTo(esperado);
        verify(crypto).wrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class));
        assertThat(nombresPedidos).containsExactly(NOMBRE);
    }

    @Test
    void unwrapEnviaRsaOaep256YSeparaElSufijo() throws Exception {
        byte[] dek = {5, 6, 7};
        Map<String, String> aad = Map.of("doc", "d-1");
        byte[] plano = Arrays.copyOf(dek, 3 + 32);
        System.arraycopy(MessageDigest.getInstance("SHA-256").digest(AadContext.canonical(aad)), 0, plano, 3, 32);
        when(crypto.unwrapKey(eq(KeyWrapAlgorithm.RSA_OAEP_256), any(byte[].class)))
            .thenReturn(new UnwrapResult(plano, KeyWrapAlgorithm.RSA_OAEP_256, "kid"));

        assertThat(kms.unwrapDek(T1, new byte[] {9}, "datos", aad).getData()).isEqualTo(dek);
        assertThatThrownBy(() -> kms.unwrapDek(T1, new byte[] {9}, "datos", Map.of("doc", "otro")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signYVerifyUsanEs256() {
        byte[] sig = new byte[64];
        when(crypto.signData(eq(SignatureAlgorithm.ES256), any(byte[].class)))
            .thenReturn(new SignResult(sig, SignatureAlgorithm.ES256, "kid"));
        when(crypto.verifyData(eq(SignatureAlgorithm.ES256), any(byte[].class), any(byte[].class)))
            .thenReturn(new VerifyResult(true, SignatureAlgorithm.ES256, "kid"));

        assertThat(kms.sign(T1, new byte[] {1}, "datos").getData()).hasSize(64);
        assertThat(kms.verify(T1, new byte[] {1}, sig, "datos")).isTrue();
        verify(crypto).signData(eq(SignatureAlgorithm.ES256), eq(new byte[] {1}));
    }

    @Test
    void elClienteCriptograficoSeCreaUnaVezPorLlave() {
        when(crypto.signData(any(SignatureAlgorithm.class), any(byte[].class)))
            .thenReturn(new SignResult(new byte[64], SignatureAlgorithm.ES256, "kid"));
        kms.sign(T1, new byte[1], "datos");
        kms.sign(T1, new byte[1], "datos");
        assertThat(nombresPedidos).containsExactly(NOMBRE);
    }

    @Test
    void disableKekDeshabilitaYLuegoEliminaLaLlaveHasheada() {
        KeyVaultKey key = mock(KeyVaultKey.class);
        KeyProperties props = new KeyProperties();
        when(key.getProperties()).thenReturn(props);
        when(keyClient.getKey(NOMBRE)).thenReturn(key);

        kms.disableKek(T1, "datos");

        ArgumentCaptor<KeyProperties> updated = ArgumentCaptor.forClass(KeyProperties.class);
        verify(keyClient).updateKeyProperties(updated.capture());
        assertThat(updated.getValue().isEnabled()).isFalse();
        verify(keyClient).beginDeleteKey(NOMBRE);
    }

    @Test
    void siNoSePuedeDeshabilitarNoSeEliminaYSigueDeshabilitadoEnProceso() {
        when(keyClient.getKey(NOMBRE)).thenThrow(new HttpResponseException("503", mock(HttpResponse.class)));
        assertThatThrownBy(() -> kms.disableKek(T1, "datos"))
            .isInstanceOf(com.idp.kms.KeyServiceUnavailableException.class)
            .hasMessage("KMS no disponible: HttpResponseException");
        verify(keyClient, never()).beginDeleteKey(any(String.class));
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", Map.of()))
            .isInstanceOf(KeyService.KeyDisabledException.class);
        verify(crypto, never()).wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class));
    }

    @Test
    void http404SeTraduceAKeyNotFound() {
        HttpResponse resp = mock(HttpResponse.class);
        when(resp.getStatusCode()).thenReturn(404);
        when(crypto.wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class)))
            .thenThrow(new ResourceNotFoundException("no existe", resp));
        assertThatThrownBy(() -> kms.wrapDek(T1, new byte[32], "datos", Map.of()))
            .isInstanceOf(KeyService.KeyNotFoundException.class);
        verify(crypto, times(1)).wrapKey(any(KeyWrapAlgorithm.class), any(byte[].class));
    }
}
