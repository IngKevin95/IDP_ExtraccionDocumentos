package com.idp.kms.gcp;

import com.google.api.gax.core.NoCredentialsProvider;
import com.google.api.gax.grpc.InstantiatingGrpcChannelProvider;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.KeyManagementServiceSettings;
import com.idp.kms.EnvelopeCryptoAutoConfiguration;
import com.idp.kms.KeyService;
import com.idp.kms.KmsAutoConfiguration;
import java.io.IOException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

/**
 * {@link KeyService} sobre Cloud KMS con {@code idp.kms.provider=gcp-kms} (ADR 0032). Credenciales por defecto
 * (ADC / Workload Identity). El endpoint de emulador solo se usa, sin credenciales y en texto plano, con
 * {@code idp.kms.gcp.emulator=true} explicito.
 */
@AutoConfiguration(after = KmsAutoConfiguration.class, before = EnvelopeCryptoAutoConfiguration.class)
@ConditionalOnProperty(name = "idp.kms.provider", havingValue = "gcp-kms")
public class GcpKmsAutoConfiguration {

    private static final String[] OBLIGATORIAS = {"project-id", "location", "key-ring"};

    /** Falla el arranque antes de crear beans (y de resolver credenciales) si falta una propiedad. */
    @Bean
    static BeanFactoryPostProcessor gcpKmsPropertiesValidator(Environment env) {
        return beanFactory -> {
            for (String p : OBLIGATORIAS) {
                if (env.getProperty("idp.kms.gcp." + p, "").isBlank()) {
                    throw new IllegalStateException("idp.kms.gcp." + p + " es obligatorio con idp.kms.provider=gcp-kms");
                }
            }
            if (env.getProperty("idp.kms.gcp.emulator", Boolean.class, false)
                    && env.getProperty("idp.kms.gcp.endpoint", "").isBlank()) {
                throw new IllegalStateException("idp.kms.gcp.endpoint es obligatorio con idp.kms.gcp.emulator=true");
            }
        };
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean
    KeyManagementServiceClient cloudKmsClient(@Value("${idp.kms.gcp.endpoint:}") String endpoint,
                                              @Value("${idp.kms.gcp.emulator:false}") boolean emulator)
            throws IOException {
        return KeyManagementServiceClient.create(settings(endpoint, emulator));
    }

    @Bean
    @ConditionalOnMissingBean(KeyService.class)
    KeyService gcpKmsKeyService(KeyManagementServiceClient client,
                                @Value("${idp.kms.gcp.project-id}") String projectId,
                                @Value("${idp.kms.gcp.location}") String location,
                                @Value("${idp.kms.gcp.key-ring}") String keyRing) {
        return new GcpKmsKeyService(new SdkGcpKmsApi(client), projectId, location, keyRing);
    }

    static KeyManagementServiceSettings settings(String endpoint, boolean emulator) throws IOException {
        KeyManagementServiceSettings.Builder builder = KeyManagementServiceSettings.newBuilder();
        if (!endpoint.isBlank()) {
            builder.setEndpoint(endpoint);
        }
        if (emulator) {
            builder.setCredentialsProvider(NoCredentialsProvider.create())
                .setTransportChannelProvider(InstantiatingGrpcChannelProvider.newBuilder()
                    .setEndpoint(endpoint).setChannelConfigurator(channel -> channel.usePlaintext()).build());
        }
        return builder.build();
    }
}
