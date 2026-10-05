package com.idp.renderer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.core.RenderException;
import com.idp.renderer.security.DocxInspector;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class DocxInspectorAllowlistTest {

    private static final String RELS_PART = "word/_rels/document.xml.rels";

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

    private Path write(String part, byte[] content) throws Exception {
        Path f = tmp.resolve("t.docx");
        Files.write(f, TestDocs.docx(Map.of(part, content)));
        return f;
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String rels(String attrs) {
        return "<Relationships><Relationship Id=\"r1\" Type=\"x\" " + attrs + "/></Relationships>";
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "Target=\"media/a.png\" TargetMode=\"External\"",
        "Target=\"media/a.png\" targetmode=\"external\"",
        "Target='media/a.png' TargetMode='Internal'",
        "Target=\"http://evil/x.png\"",
        "Target='HTTPS://evil/x.png'",
        "Target=\"file:///c:/x\"",
        "Target=\"/abs/path\"",
        "Target=\"\\\\host\\share\"",
        "Target=\"//evil/x\"",
        "TargetMode=\"Ext&#101;rnal\" Target=\"a.png\"",
        "Target=\"ht&#116;p://evil/x\"",
        "Id2=\"x\""
    })
    void allowlist_relacionNoRelativa_seRechaza(String attrs) throws Exception {
        Path f = write(RELS_PART, utf8(rels(attrs)));
        assertThatThrownBy(() -> inspector().inspect(f)).isInstanceOf(RenderException.class);
    }

    @Test
    void allowlist_relsUtf16ConBom_seRechaza() throws Exception {
        byte[] body = rels("Target=\"a.png\"").getBytes(StandardCharsets.UTF_16LE);
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFF;
        withBom[1] = (byte) 0xFE;
        System.arraycopy(body, 0, withBom, 2, body.length);
        Path f = write(RELS_PART, withBom);
        assertThatThrownBy(() -> inspector().inspect(f)).isInstanceOf(RenderException.class);
    }

    @Test
    void allowlist_relsUtf16SinBom_conNul_seRechaza() throws Exception {
        Path f = write(RELS_PART, rels("Target=\"a.png\" TargetMode=\"External\"").getBytes(StandardCharsets.UTF_16LE));
        assertThatThrownBy(() -> inspector().inspect(f)).isInstanceOf(RenderException.class);
    }

    @Test
    void allowlist_wordXmlUtf16_seRechaza() throws Exception {
        byte[] body = "<w:document/>".getBytes(StandardCharsets.UTF_16BE);
        byte[] withBom = new byte[body.length + 2];
        withBom[0] = (byte) 0xFE;
        withBom[1] = (byte) 0xFF;
        System.arraycopy(body, 0, withBom, 2, body.length);
        Path f = write("word/styles.xml", withBom);
        assertThatThrownBy(() -> inspector().inspect(f)).isInstanceOf(RenderException.class);
    }

    @Test
    void allowlist_wordXmlConNul_seRechaza() throws Exception {
        Path f = write("word/settings.xml", new byte[] {'<', 'a', 0, '/', '>'});
        assertThatThrownBy(() -> inspector().inspect(f)).isInstanceOf(RenderException.class);
    }

    @Test
    void allowlist_wordXmlConReferenciaDeCaracterEnAtributo_seRechaza() throws Exception {
        Path f = write("word/styles.xml", utf8("<w:style w:name=\"Ext&#101;rnal\"/>"));
        assertThatThrownBy(() -> inspector().inspect(f)).isInstanceOf(RenderException.class);
    }

    @Test
    void allowlist_relacionesRelativasLimpias_seAceptan() throws Exception {
        Path f = write(RELS_PART, utf8(rels("Target='media/a.png'")));
        assertThatCode(() -> inspector().inspect(f)).doesNotThrowAnyException();
    }

    @Test
    void allowlist_entidadPredefinidaEnAtributoWord_seAcepta() throws Exception {
        Path f = write("word/styles.xml", utf8("<w:style w:name=\"A &amp; B\"/>"));
        assertThatCode(() -> inspector().inspect(f)).doesNotThrowAnyException();
    }
}
