package com.idp.extraction;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.FileSystemResource;

/** El application.yml de extraction alimenta idp.* desde extraction.* para las autoconfiguraciones (ADR 0032). */
class StorageKmsPropertyWiringTest {

    @Test
    void lasPropiedadesExtractionAlimentanLasPropiedadesIdp() {
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
            .withPropertyValues("extraction.control.url=jdbc:postgresql://db/control",
                "extraction.storage.endpoint=http://s3:9000", "extraction.storage.region=eu-west-1",
                "extraction.storage.access-key=ak", "extraction.storage.secret-key=sk",
                "extraction.openbao.address=https://bao:8200", "extraction.openbao.token=tok",
                "extraction.openbao.transit-mount=tr", "extraction.openbao.ssl-bundle=bundle")
            .run(ctx -> {
                var env = ctx.getEnvironment();
                assertEquals("jdbc:postgresql://db/control", env.getProperty("idp.control-db.url"));
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
