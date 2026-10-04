package com.idp.renderer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;

import com.idp.renderer.api.GlobalExceptionHandler;
import com.idp.renderer.api.RenderController;
import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.convert.DocumentConverter;
import com.idp.renderer.core.ImageProcessor;
import com.idp.renderer.core.PdfProcessor;
import com.idp.renderer.core.RenderService;
import com.idp.renderer.security.DocxInspector;
import com.idp.renderer.security.FileTypeValidator;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import javax.imageio.ImageIO;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class RenderEndpointTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final FakeAntivirusScanner scanner = new FakeAntivirusScanner();
    private int converterCalls;

    private RendererProperties props(int maxPages, long maxUncompressed, Duration renderTimeout) {
        return new RendererProperties(
                new RendererProperties.Clamav("127.0.0.1", 3310, Duration.ofSeconds(1), Duration.ofSeconds(1)),
                new RendererProperties.Limits(50L * 1024 * 1024, maxPages, maxUncompressed, 25_000_000L,
                        renderTimeout),
                new RendererProperties.Libreoffice("soffice", Duration.ofSeconds(30)),
                new RendererProperties.Raster(72));
    }

    private MockMvc mvc(RendererProperties p) {
        DocumentConverter converter = (docx, work) -> {
            converterCalls++;
            try {
                Path pdf = work.resolve("converted.pdf");
                Files.write(pdf, TestDocs.pdf(1, "Convertido desde DOCX"));
                return pdf;
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        };
        RenderService service = new RenderService(p, scanner, new FileTypeValidator(), new DocxInspector(p),
                converter, new PdfProcessor(p), new ImageProcessor(p), new SimpleMeterRegistry());
        return MockMvcBuilders.standaloneSetup(new RenderController(service))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    private MockMvc mvc() {
        return mvc(props(100, 256L * 1024 * 1024, Duration.ofSeconds(60)));
    }

    private MvcResult post(MockMvc mvc, String name, byte[] content) throws Exception {
        return mvc.perform(multipart("/v1/render")
                .file(new MockMultipartFile("file", name, "application/octet-stream", content))).andReturn();
    }

    private static JsonNode textLayer(Map<String, byte[]> zip) {
        return JSON.readTree(zip.get("text_layer.json"));
    }

    private static void assertPng(byte[] png) throws IOException {
        assertThat(ImageIO.read(new ByteArrayInputStream(png))).isNotNull();
    }

    private void assertError(MvcResult r, int status, String code) throws Exception {
        assertThat(r.getResponse().getStatus()).isEqualTo(status);
        JsonNode body = JSON.readTree(r.getResponse().getContentAsString());
        assertThat(body.get("code").asText()).isEqualTo(code);
        assertThat(body.get("message").asText()).isNotBlank();
        assertThat(body.get("incidentId").asText()).matches("[0-9a-f-]{36}");
    }

    @Test
    void ac01_pdfValido_devuelveZipConPngYTextoNativoPorPagina() throws Exception {
        MvcResult r = post(mvc(), "oficio.pdf", TestDocs.pdf(2, "Embargo oficio"));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        assertThat(r.getResponse().getContentType()).isEqualTo("application/zip");
        Map<String, byte[]> zip = TestDocs.unzip(r.getResponse().getContentAsByteArray());
        assertThat(zip).containsOnlyKeys("page_1.png", "page_2.png", "text_layer.json");
        assertPng(zip.get("page_1.png"));
        assertPng(zip.get("page_2.png"));
        JsonNode pages = textLayer(zip).get("pages");
        assertThat(pages).hasSize(2);
        assertThat(pages.get(0).get("page").asInt()).isEqualTo(1);
        assertThat(pages.get(0).get("text").asText()).contains("Embargo oficio 1");
        assertThat(pages.get(1).get("text").asText()).contains("Embargo oficio 2");
        assertThat(scanner.calls).isEqualTo(1);
    }

    @Test
    void ac01_pdfSinCapaDeTexto_devuelveTextoVacioSinError() throws Exception {
        MvcResult r = post(mvc(), "escaneo.pdf", TestDocs.pdf(1, "", PDRectangle.A4, false));
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Map<String, byte[]> zip = TestDocs.unzip(r.getResponse().getContentAsByteArray());
        assertThat(textLayer(zip).get("pages").get(0).get("text").asText()).isEmpty();
    }

    @Test
    void ac02_docxValido_seConvierteARasterizaYExtraeTexto() throws Exception {
        MvcResult r = post(mvc(), "oficio.docx", TestDocs.docx());
        assertThat(r.getResponse().getStatus()).isEqualTo(200);
        Map<String, byte[]> zip = TestDocs.unzip(r.getResponse().getContentAsByteArray());
        assertThat(zip).containsOnlyKeys("page_1.png", "text_layer.json");
        assertPng(zip.get("page_1.png"));
        assertThat(textLayer(zip).get("pages").get(0).get("text").asText()).contains("Convertido desde DOCX");
        assertThat(converterCalls).isEqualTo(1);
    }

    @Test
    void ac03_imagenesJpgPngTiff_sePasanAPngConTextoVacio() throws Exception {
        for (String fmt : new String[] {"jpeg", "png", "tiff"}) {
            MvcResult r = post(mvc(), "scan." + fmt, TestDocs.image(fmt));
            assertThat(r.getResponse().getStatus()).as(fmt).isEqualTo(200);
            Map<String, byte[]> zip = TestDocs.unzip(r.getResponse().getContentAsByteArray());
            assertThat(zip).as(fmt).containsOnlyKeys("page_1.png", "text_layer.json");
            assertPng(zip.get("page_1.png"));
            JsonNode pages = textLayer(zip).get("pages");
            assertThat(pages).hasSize(1);
            assertThat(pages.get(0).get("text").asText()).isEmpty();
        }
    }

    @Test
    void ac04_eicar_respondeMalwareDetectadoSinParsear() throws Exception {
        MvcResult r = post(mvc(), "eicar.pdf", TestDocs.EICAR.getBytes(StandardCharsets.US_ASCII));
        assertError(r, 400, "ERR_MALWARE_DETECTED");
        assertThat(converterCalls).isZero();
    }

    @Test
    void ac04_eicarAnexadoAUnPdf_seRechazaAntesDeRasterizar() throws Exception {
        byte[] pdf = TestDocs.pdf(1, "x");
        byte[] eicar = TestDocs.EICAR.getBytes(StandardCharsets.US_ASCII);
        byte[] both = new byte[pdf.length + eicar.length];
        System.arraycopy(pdf, 0, both, 0, pdf.length);
        System.arraycopy(eicar, 0, both, pdf.length, eicar.length);
        assertError(post(mvc(), "x.pdf", both), 400, "ERR_MALWARE_DETECTED");
    }

    @Test
    void ac05_pdfConJavaScript_seRechaza() throws Exception {
        assertError(post(mvc(), "js.pdf", TestDocs.pdfWithJavaScript()), 422, "ERR_ACTIVE_CONTENT");
    }

    @Test
    void ac05_pdfConAdjuntoEmbebido_seRechaza() throws Exception {
        assertError(post(mvc(), "adj.pdf", TestDocs.pdfWithAttachment()), 422, "ERR_ACTIVE_CONTENT");
    }

    @Test
    void ac06_docxConMacrosVba_responde422() throws Exception {
        byte[] docx = TestDocs.docx(Map.of("word/vbaProject.bin", new byte[] {1, 2, 3}));
        assertError(post(mvc(), "macro.docx", docx), 422, "ERR_CONVERSION_REJECTED");
        assertThat(converterCalls).isZero();
    }

    @Test
    void ac06_docmDisfrazadoDeDocx_porContentTypeMacroEnabled_responde422() throws Exception {
        byte[] ct = ("<Types><Override PartName=\"/word/document.xml\" "
                + "ContentType=\"application/vnd.ms-word.document.macroEnabled.main+xml\"/></Types>")
                .getBytes(StandardCharsets.UTF_8);
        byte[] docx = TestDocs.docx(Map.of("[Content_Types].xml", ct));
        assertError(post(mvc(), "disfraz.docx", docx), 422, "ERR_CONVERSION_REJECTED");
    }

    @Test
    void ac07_ejecutableRenombradoAPdf_responde400() throws Exception {
        byte[] elf = new byte[256];
        elf[0] = 0x7F;
        elf[1] = 'E';
        elf[2] = 'L';
        elf[3] = 'F';
        assertError(post(mvc(), "factura.pdf", elf), 400, "ERR_UNSUPPORTED_FORMAT");
        byte[] exe = new byte[256];
        exe[0] = 'M';
        exe[1] = 'Z';
        assertError(post(mvc(), "factura.pdf", exe), 400, "ERR_UNSUPPORTED_FORMAT");
    }

    @Test
    void ac07_zipQueNoEsDocx_responde400() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.putNextEntry(new ZipEntry("a.txt"));
            z.write(1);
            z.closeEntry();
        }
        assertError(post(mvc(), "x.docx", bos.toByteArray()), 400, "ERR_UNSUPPORTED_FORMAT");
    }

    @Test
    void ac08_pdfQueExcedeMaximoDePaginas_responde413() throws Exception {
        MockMvc m = mvc(props(3, 256L * 1024 * 1024, Duration.ofSeconds(60)));
        assertThat(post(m, "ok.pdf", TestDocs.pdf(3, "p")).getResponse().getStatus()).isEqualTo(200);
        assertError(post(m, "largo.pdf", TestDocs.pdf(4, "p")), 413, "ERR_LIMIT_EXCEEDED");
    }

    @Test
    void ac08_docxBombaDeDescompresion_responde413() throws Exception {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bos)) {
            z.setLevel(Deflater.BEST_COMPRESSION);
            for (Map.Entry<String, byte[]> e : TestDocs.unzip(TestDocs.docx()).entrySet()) {
                z.putNextEntry(new ZipEntry(e.getKey()));
                z.write(e.getValue());
                z.closeEntry();
            }
            z.putNextEntry(new ZipEntry("word/media/bomba.bin"));
            byte[] zeros = new byte[1 << 20];
            for (int i = 0; i < 20; i++) {
                z.write(zeros);
            }
            z.closeEntry();
        }
        MockMvc m = mvc(props(100, 5L * 1024 * 1024, Duration.ofSeconds(60)));
        assertError(post(m, "bomba.docx", bos.toByteArray()), 413, "ERR_LIMIT_EXCEEDED");
    }

    @Test
    void ac08_paginaGigante_responde413() throws Exception {
        byte[] pdf = TestDocs.pdf(1, "x", new PDRectangle(14400, 14400), false);
        assertError(post(mvc(), "gigante.pdf", pdf), 413, "ERR_LIMIT_EXCEEDED");
    }

    @Test
    void ac08_tiempoMaximoDeProcesamiento_seInterrumpe() throws Exception {
        MockMvc m = mvc(props(100, 256L * 1024 * 1024, Duration.ZERO));
        assertError(post(m, "lento.pdf", TestDocs.pdf(2, "p")), 413, "ERR_LIMIT_EXCEEDED");
    }

    @Test
    void pdfCorrupto_responde400() throws Exception {
        byte[] bad = "%PDF-1.7\nesto no es un pdf valido".getBytes(StandardCharsets.US_ASCII);
        assertError(post(mvc(), "roto.pdf", bad), 400, "ERR_VALIDATION");
    }

    @Test
    void archivoVacio_responde400() throws Exception {
        assertError(post(mvc(), "vacio.pdf", new byte[0]), 400, "ERR_VALIDATION");
    }

    @Test
    void sinParteFile_responde400() throws Exception {
        MvcResult r = mvc().perform(multipart("/v1/render")).andReturn();
        assertError(r, 400, "ERR_VALIDATION");
    }
}
