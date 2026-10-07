package com.idp.kms;

import java.util.List;
import javax.net.ssl.SSLContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.ssl.SslBundles;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;
import org.springframework.web.client.RestClient;

/**
 * Selecciona el {@link KeyService} por {@code idp.kms.provider} (ADR 0032). El respaldo en memoria solo existe
 * con {@code idp.security.dev-mode=true} y sin proveedor: fuera de dev-mode nunca se cae a el.
 */
@AutoConfiguration
public class KmsAutoConfiguration {

    static final List<String> PROVEEDORES = List.of("openbao", "aws-kms", "gcp-kms", "azure-keyvault");
    private static final List<String> IMPLEMENTADOS = List.of("openbao");

    /** Falla el arranque (AC-02) antes de crear beans, con un mensaje que lista los valores validos. */
    @Bean
    static BeanFactoryPostProcessor kmsProviderValidator(Environment env) {
        return beanFactory -> validar(env.getProperty("idp.kms.provider", ""),
            !env.getProperty("idp.control-db.url", "").isBlank());
    }

    static void validar(String provider, boolean controlDbDefinida) {
        if (provider.isBlank()) {
            if (controlDbDefinida) {
                throw new IllegalStateException("idp.kms.provider es obligatorio cuando idp.control-db.url esta definido."
                    + " Valores validos: " + String.join("|", PROVEEDORES));
            }
            return;
        }
        if (!PROVEEDORES.contains(provider)) {
            throw new IllegalStateException("idp.kms.provider desconocido: '" + provider + "'."
                + " Valores validos: " + String.join("|", PROVEEDORES));
        }
        if (!IMPLEMENTADOS.contains(provider)) {
            throw new IllegalStateException("idp.kms.provider='" + provider + "': proveedor no implementado todavia."
                + " Implementados: " + String.join("|", IMPLEMENTADOS));
        }
    }

    @Bean
    @ConditionalOnProperty(name = "idp.kms.provider", havingValue = "openbao")
    @ConditionalOnMissingBean(KeyService.class)
    KeyService openBaoKeyService(ObjectProvider<RestClient.Builder> builder,
                                 @Value("${idp.openbao.address:}") String address,
                                 @Value("${idp.openbao.token}") String token,
                                 @Value("${idp.openbao.transit-mount:transit}") String mount,
                                 @Value("${idp.security.dev-mode:false}") boolean devMode,
                                 @Value("${idp.openbao.ssl-bundle:}") String sslBundleName,
                                 ObjectProvider<SslBundles> bundles) {
        if (address.isBlank()) {
            throw new IllegalStateException("idp.openbao.address es obligatorio con idp.kms.provider=openbao");
        }
        SSLContext ssl = null;
        if (!sslBundleName.isBlank()) {
            ssl = bundles.getObject().getBundle(sslBundleName).createSslContext();
        }
        return new OpenBaoTransitKeyService(builder.getIfAvailable(RestClient::builder), address, () -> token, mount,
            devMode, ssl);
    }

    @Bean
    @ConditionalOnExpression("'${idp.kms.provider:}'.isEmpty() && ${idp.security.dev-mode:false}")
    @ConditionalOnMissingBean(KeyService.class)
    KeyService inMemoryKeyService() {
        return new InMemoryKeyService();
    }
}
