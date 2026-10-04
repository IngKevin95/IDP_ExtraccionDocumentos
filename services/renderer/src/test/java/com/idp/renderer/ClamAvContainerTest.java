package com.idp.renderer;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.security.AntivirusScanner.ScanResult;
import com.idp.renderer.security.ClamAvScanner;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** ClamAV real (AC-04). Se omite sin Docker; en CI corre contra clamav/clamav. */
@Testcontainers(disabledWithoutDocker = true)
class ClamAvContainerTest {

    @Container
    static final GenericContainer<?> CLAMAV = new GenericContainer<>("clamav/clamav:1.4")
            .withExposedPorts(3310)
            .waitingFor(Wait.forLogMessage(".*socket found, clamd started.*\\n", 1))
            .withStartupTimeout(Duration.ofMinutes(8));

    @TempDir
    Path dir;

    private ClamAvScanner scanner() {
        return new ClamAvScanner(new RendererProperties(
                new RendererProperties.Clamav(CLAMAV.getHost(), CLAMAV.getMappedPort(3310),
                        Duration.ofSeconds(5), Duration.ofSeconds(60)),
                new RendererProperties.Limits(1, 1, 1, 1, Duration.ofSeconds(1)),
                new RendererProperties.Libreoffice("soffice", Duration.ofSeconds(1)),
                new RendererProperties.Raster(72)));
    }

    @Test
    void ac04_clamavReal_detectaEicarYAceptaLimpio() throws Exception {
        ScanResult infected = scanner().scan(Files.writeString(dir.resolve("eicar.com"), TestDocs.EICAR));
        assertThat(infected.clean()).isFalse();
        assertThat(infected.signature()).containsIgnoringCase("eicar");
        assertThat(scanner().scan(Files.write(dir.resolve("ok.pdf"), TestDocs.pdf(1, "limpio"))).clean())
                .isTrue();
    }
}
