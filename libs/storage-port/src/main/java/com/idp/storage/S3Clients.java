package com.idp.storage;

import java.net.URI;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;

/** Fabrica de S3Client con endpoint configurable (MinIO, Ceph, LocalStack) y path-style. */
public final class S3Clients {

    private S3Clients() {
    }

    /**
     * @param endpoint  null para el endpoint por defecto de AWS
     * @param accessKey null para usar la cadena de credenciales por defecto (roles, IRSA, etc.)
     */
    public static S3Client create(URI endpoint, String region, String accessKey, String secretKey,
                                  boolean pathStyle) {
        var builder = S3Client.builder().region(Region.of(region))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(pathStyle).build());
        if (endpoint != null) {
            builder.endpointOverride(endpoint);
        }
        if (accessKey != null) {
            builder.credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)));
        }
        return builder.build();
    }
}
