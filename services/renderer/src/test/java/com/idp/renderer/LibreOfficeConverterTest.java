package com.idp.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.convert.LibreOfficeConverter;
import com.idp.renderer.core.Deadline;
import com.idp.renderer.core.PdfProcessor;
import com.idp.renderer.core.RenderException;
import com.idp.renderer.core.ResultPackage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LibreOfficeConverterTest {

    private static final String JAVA = Path.of(System.getProperty("java.home"), "bin", "java").toString();

    @TempDir
    Path dir;

    private static RendererProperties props(String binary, Duration timeout) {
        return new RendererProperties(
                new RendererProperties.Clamav("127.0.0.1", 3310, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new RendererProperties.Limits(50L * 1024 * 1024, 100, 256L * 1024 * 1024, 25_000_000L,
                        Duration.ofSeconds(60), 2),
                new RendererProperties.Libreoffice(binary, timeout),
                new RendererProperties.Raster(72));
    }

    private Path docx() throws Exception {
        return Files.write(dir.resolve("in.bin"), TestDocs.docx());
    }

    private Path work() throws Exception {
        return Files.createDirectory(dir.resolve("w"));
    }

    private static boolean sofficeAvailable() {
        try {
            Process p = new ProcessBuilder("soffice", "--version").redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
            return p.waitFor(60, TimeUnit.SECONDS) && p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }

    @Test
    void ac02_libreOfficeReal_convierteDocxYSeRasteriza() throws Exception {
        assumeTrue(sofficeAvailable(), "soffice no disponible");
        RendererProperties p = props("soffice", Duration.ofSeconds(90));
        Path pdf = new LibreOfficeConverter(p).convertToPdf(docx(), work());
        try (ResultPackage pkg = new ResultPackage()) {
            int pages = new PdfProcessor(p).process(pdf, pkg, new Deadline(Duration.ofSeconds(60)));
            pkg.finish();
            assertThat(pages).isEqualTo(1);
        }
    }

    @Test
    void ac02_timeoutDelProceso_matadoYReportadoComoErrTimeout() throws Exception {
        LibreOfficeConverter c = new LibreOfficeConverter(props(JAVA, Duration.ofMillis(1)));
        assertThatThrownBy(() -> c.convertToPdf(docx(), work()))
                .isInstanceOfSatisfying(RenderException.class, e -> {
                    assertThat(e.status()).isEqualTo(500);
                    assertThat(e.code()).isEqualTo("ERR_TIMEOUT");
                });
    }

    @Test
    void ac06_conversionFallida_seRechazaCon422() throws Exception {
        LibreOfficeConverter c = new LibreOfficeConverter(props(JAVA, Duration.ofSeconds(60)));
        assertThatThrownBy(() -> c.convertToPdf(docx(), work()))
                .isInstanceOfSatisfying(RenderException.class, e -> {
                    assertThat(e.status()).isEqualTo(422);
                    assertThat(e.code()).isEqualTo("ERR_CONVERSION_REJECTED");
                });
    }

    @Test
    void ac02_binarioInexistente_responde500ConversorNoDisponible() throws Exception {
        LibreOfficeConverter c = new LibreOfficeConverter(props("no-existe-soffice-xyz", Duration.ofSeconds(5)));
        assertThatThrownBy(() -> c.convertToPdf(docx(), work()))
                .isInstanceOfSatisfying(RenderException.class,
                        e -> assertThat(e.code()).isEqualTo("ERR_CONVERTER_UNAVAILABLE"));
    }
}
