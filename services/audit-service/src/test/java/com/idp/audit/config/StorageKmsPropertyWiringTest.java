package com.idp.audit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

/** El application.yml de audit alimenta idp.storage.* e idp.openbao.* que leen las autoconfiguraciones (ADR 0032). */
class StorageKmsPropertyWiringTest {

    @Test
    void lasVariablesDeEntornoLlegaranALasPropiedadesDeLasAutoconfiguraciones() {
        new ApplicationContextRunner()
            // El application.yml de src/main/resources, no el de src/test/resources que lo tapa en el classpath.
            .withInitializer(ctx -> {
                try {
                    new YamlPropertySourceLoader()
                        .load("main", new FileSystemResource("src/main/resources/application.yml"))
                        .forEach(ctx.getEnvironment().getPropertySources()::addLast);
                } catch (java.io.IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            })
            .withPropertyValues("AUDIT_WORM_BUCKET=worm", "AUDIT_WORM_ENDPOINT=http://s3:9000",
                "AUDIT_WORM_REGION=eu-west-1", "AUDIT_WORM_ACCESS_KEY=ak", "AUDIT_WORM_SECRET_KEY=sk",
                "OPENBAO_ADDR=https://bao:8200", "OPENBAO_TOKEN=tok", "OPENBAO_TRANSIT_MOUNT=tr",
                "IDP_AUDIT_KMS_OPENBAO_SSL_BUNDLE=bundle")
            .run(ctx -> {
                var env = ctx.getEnvironment();
                assertEquals("true", env.getProperty("idp.storage.immutable"));
                assertEquals("worm", env.getProperty("idp.storage.bucket"));
                assertEquals("http://s3:9000", env.getProperty("idp.storage.endpoint"));
                assertEquals("eu-west-1", env.getProperty("idp.storage.region"));
                assertEquals("ak", env.getProperty("idp.storage.access-key"));
                assertEquals("sk", env.getProperty("idp.storage.secret-key"));
                assertEquals("true", env.getProperty("idp.storage.path-style"));
                assertEquals("https://bao:8200", env.getProperty("idp.openbao.address"));
                assertEquals("tok", env.getProperty("idp.openbao.token"));
                assertEquals("tr", env.getProperty("idp.openbao.transit-mount"));
                assertEquals("bundle", env.getProperty("idp.openbao.ssl-bundle"));
            });
    }
}
