package com.idp.kms.azure;

import com.azure.core.credential.AccessToken;
import com.azure.core.credential.TokenCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.idp.kms.EnvelopeCryptoAutoConfiguration;
import com.idp.kms.KeyService;
import com.idp.kms.KmsAutoConfiguration;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import reactor.core.publisher.Mono;

/**
 * {@link KeyService} de Azure Key Vault con {@code idp.kms.provider=azure-keyvault}. Va antes de
 * {@link EnvelopeCryptoAutoConfiguration}, cuyo {@code @ConditionalOnBean(KeyService)} necesita el bean ya registrado.
 * <p>
 * Credencial: {@code DefaultAzureCredential} (Workload Identity en AKS, identidad administrada, CLI en local). Solo
 * con {@code idp.kms.azure.emulator=true} (exige {@code idp.security.dev-mode=true} y un vault que no sea de Azure)
 * se usa una credencial estatica y se admite http (Lowkey Vault u otro).
 * </p>
 * <p>
 * Dependencias: azure-core-http-netty 1.16 espera Netty 4.1 y Spring Boot gestiona Netty 4.2. Los tests no lo ejercitan
 * contra Key Vault real: vigilar este par al subir de version, o excluir azure-core-http-netty y usar
 * azure-core-http-jdk-httpclient si aparecen {@code NoSuchMethodError} de Netty.
 * </p>
 */
@AutoConfiguration(after = KmsAutoConfiguration.class, before = EnvelopeCryptoAutoConfiguration.class)
@ConditionalOnProperty(name = "idp.kms.provider", havingValue = "azure-keyvault")
public class AzureKeyVaultKmsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(KeyService.class)
    KeyService azureKeyVaultKeyService(@Value("${idp.kms.azure.vault-url:}") String vaultUrl,
                                       @Value("${idp.kms.azure.emulator:false}") boolean emulator,
                                       @Value("${idp.security.dev-mode:false}") boolean devMode) {
        if (vaultUrl.isBlank()) {
            throw new IllegalStateException("idp.kms.azure.vault-url es obligatorio con idp.kms.provider=azure-keyvault");
        }
        if (emulator && (!devMode || esVaultDeAzure(vaultUrl))) {
            throw new IllegalStateException("idp.kms.azure.emulator=true exige idp.security.dev-mode=true y un vault"
                + " que no sea de Azure");
        }
        if (!emulator && !vaultUrl.startsWith("https://")) {
            throw new IllegalStateException("idp.kms.azure.vault-url debe ser https salvo con idp.kms.azure.emulator=true");
        }
        return new AzureKeyVaultKeyService(SdkKeyVaultApi.create(vaultUrl, credential(emulator), null, emulator));
    }

    /** La credencial estatica solo existe con el emulador explicito; si no, DefaultAzureCredential. */
    static TokenCredential credential(boolean emulator) {
        return emulator
            ? request -> Mono.just(new AccessToken("emulator", OffsetDateTime.now().plusHours(1)))
            : new DefaultAzureCredentialBuilder().build();
    }

    private static boolean esVaultDeAzure(String vaultUrl) {
        String host = java.net.URI.create(vaultUrl).getHost();
        host = host == null ? "" : host.toLowerCase(java.util.Locale.ROOT);
        return host.endsWith(".azure.net") || host.endsWith(".azure.cn") || host.endsWith(".usgovcloudapi.net")
            || host.endsWith(".microsoftazure.de");
    }
}
