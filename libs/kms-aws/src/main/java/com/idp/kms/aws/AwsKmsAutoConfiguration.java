package com.idp.kms.aws;

import com.idp.kms.EnvelopeCryptoAutoConfiguration;
import com.idp.kms.KeyService;
import com.idp.kms.KmsAutoConfiguration;
import java.net.URI;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/** {@link KeyService} sobre AWS KMS con {@code idp.kms.provider=aws-kms} (ADR 0032). */
@AutoConfiguration(after = KmsAutoConfiguration.class, before = EnvelopeCryptoAutoConfiguration.class)
@ConditionalOnProperty(name = "idp.kms.provider", havingValue = "aws-kms")
public class AwsKmsAutoConfiguration {

    /**
     * Credenciales estaticas solo con {@code emulator=true} explicito (LocalStack); en otro caso vacio, y el SDK usa
     * la cadena por defecto (Pod Identity, IRSA, rol de instancia) aunque haya un endpoint personalizado.
     */
    static Optional<AwsCredentialsProvider> staticCredentials(boolean emulator, String accessKey, String secretKey) {
        if (!emulator) {
            return Optional.empty();
        }
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException(
                "idp.kms.aws.access-key y idp.kms.aws.secret-key son obligatorios con idp.kms.aws.emulator=true");
        }
        return Optional.of(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
    }

    @Bean
    @ConditionalOnMissingBean(KmsClient.class)
    KmsClient awsKmsClient(@Value("${idp.kms.aws.region:}") String region,
                           @Value("${idp.kms.aws.endpoint:}") String endpoint,
                           @Value("${idp.kms.aws.emulator:false}") boolean emulator,
                           @Value("${idp.kms.aws.access-key:}") String accessKey,
                           @Value("${idp.kms.aws.secret-key:}") String secretKey) {
        var builder = KmsClient.builder();
        if (!region.isBlank()) {
            builder.region(Region.of(region));
        }
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        staticCredentials(emulator, accessKey, secretKey).ifPresent(builder::credentialsProvider);
        return builder.build();
    }

    @Bean
    @ConditionalOnMissingBean(KeyService.class)
    KeyService awsKmsKeyService(KmsClient client,
                                @Value("${idp.kms.aws.deletion-window-days:7}") int deletionWindowDays) {
        return new AwsKmsKeyService(client, deletionWindowDays);
    }
}
