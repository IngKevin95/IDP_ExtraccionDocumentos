package com.idp.renderer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.config.RendererTlsGuard;
import com.idp.renderer.core.RenderException;
import com.idp.renderer.security.DocxInspector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RendererHardeningTest {

    @TempDir
    Path tmp;

    private DocxInspector inspector() {
        return new DocxInspector(new RendererProperties(
                new RendererProperties.Clamav("127.0.0.1", 3310, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new RendererProperties.Limits(50L * 1024 * 1024, 100, 256L * 1024 * 1024, 25_000_000L,
                        Duration.ofSeconds(60), 2),
                new RendererProperties.Libreoffice("soffice", Duration.ofSeconds(30)),
                new RendererProperties.Raster(72)));
    }

    private Path write(Map<String, byte[]> extra) throws Exception {
        Path f = tmp.resolve("t.docx");
        Files.write(f, TestDocs.docx(extra));
        return f;
    }

    @Test
    void h2_tlsObligatorioSalvoDevMode() {
        assertThatThrownBy(() -> new RendererTlsGuard("", "", true, false))
                .isInstanceOf(IllegalStateException.class);
        assertThatCode(() -> new RendererTlsGuard("renderer", "", true, false)).doesNotThrowAnyException();
        assertThatCode(() -> new RendererTlsGuard("", "", true, true)).doesNotThrowAnyException();
    }

    @Test
    void h2_docxConRelacionExterna_seRechaza() throws Exception {
        String rels = "<Relationships><Relationship Id=\"r1\" Type=\"x\" Target=\"http://evil/x.png\" "
                + "TargetMode = 'External' /></Relationships>";
        Path f = write(Map.of("word/_rels/document.xml.rels", rels.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> inspector().inspect(f)).isInstanceOf(RenderException.class);
    }

    @Test
    void h2_docxConIncludePictureRemotoEnRunsPartidos_seRechaza() throws Exception {
        String doc = "<w:document><w:body><w:p><w:r><w:instrText>INCLUDE</w:instrText></w:r>"
                + "<w:r><w:instrText>PICTURE \"http://evil/x.png\"</w:instrText></w:r></w:p></w:body></w:document>";
        Path f = write(Map.of("word/document.xml", doc.getBytes(StandardCharsets.UTF_8)));
        assertThatThrownBy(() -> inspector().inspect(f)).isInstanceOf(RenderException.class);
    }

    @Test
    void h2_docxLimpio_seAcepta() throws Exception {
        assertThatCode(() -> inspector().inspect(write(Map.of()))).doesNotThrowAnyException();
    }
}
