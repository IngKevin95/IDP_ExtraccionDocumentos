package com.idp.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.synthetic.BatchGenerator.Modo;
import com.idp.synthetic.BatchGenerator.Opciones;
import com.idp.synthetic.OficioGenerator.Plan;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Golden set versionado (20 oficios) y ejemplos few-shot. Si el generador cambia a proposito, regenerar con:
 * golden: --salida golden-set --semilla 20260101 --cantidad 5 --modo ambos;
 * ejemplos: --salida ejemplos --semilla 7001 --cantidad 2 --modo nativo --few-shot
 * (desde tools/synthetic-oficios: java -jar target/synthetic-oficios-*-exec.jar ...). Identificaciones en rangos
 * reservados: cedula 99NNNNNNNN, NIT base 999NNNNNN, radicado con especialidad 99 (ver Identificaciones).
 */
class GoldenSetTest {

    static final Path GOLDEN = Path.of("golden-set");
    static final Path EJEMPLOS = Path.of("ejemplos");
    static final long SEMILLA_GOLDEN = 20260101L;
    static final long SEMILLA_EJEMPLOS = 7001L;
    private static final ObjectMapper JSON = new ObjectMapper();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    @Test
    void goldenSetTiene20OficiosSinteticosCon5PorTipologiaYTodosLosDefectos() throws IOException {
        JsonNode manifest = JSON.readTree(GOLDEN.resolve("manifest.json").toFile());
        assertThat(manifest.get("semilla").asLong()).isEqualTo(SEMILLA_GOLDEN);
        JsonNode oficios = manifest.get("oficios");
        assertThat(oficios).hasSize(20);
        Set<String> defectos = new HashSet<>();
        for (Tipo t : Tipo.values()) {
            long n = 0;
            for (JsonNode o : oficios) {
                if (o.get("id").asText().startsWith(t.prefijo() + "-")) {
                    n++;
                    o.get("defectos").forEach(d -> defectos.add(d.asText()));
                }
            }
            assertThat(n).as(t.name()).isEqualTo(5);
        }
        assertThat(defectos).containsExactlyInAnyOrder("MONTO_INCONSISTENTE", "TABLA_NO_SUMA", "INJECTION_PROMPT",
            "CAMPO_FALTANTE");
    }

    @Test
    void archivosDelGoldenSetCoincidenConElManifestYSonSinteticos() throws IOException {
        for (JsonNode o : JSON.readTree(GOLDEN.resolve("manifest.json").toFile()).get("oficios")) {
            Path dir = GOLDEN.resolve(o.get("id").asText());
            o.get("sha256").fields().forEachRemaining(e -> {
                try {
                    assertThat(BatchGenerator.sha256(Files.readAllBytes(dir.resolve(e.getKey()))))
                        .as(dir.resolve(e.getKey()).toString()).isEqualTo(e.getValue().asText());
                } catch (IOException ex) {
                    throw new IllegalStateException(ex);
                }
            });
            assertThat(Files.readString(dir.resolve("texto.txt"))).contains("SIN VALOR LEGAL");
            JsonNode verdad = JSON.readTree(dir.resolve("verdad.json").toFile());
            assertThat(verdad.get("esquema").asText()).isEqualTo(GroundTruth.ESQUEMA);
            assertThat(verdad.get("tablas").get("demandados").size()).isBetween(2, 6);
            assertThat(verdad.get("campos_criticos")).isNotEmpty();
        }
    }

    @Test
    void goldenSetYEjemplosVersionadosCoincidenConElGeneradorActual(@TempDir Path tmp) throws IOException {
        regenerarYComparar(tmp.resolve("g"), GOLDEN, SEMILLA_GOLDEN, 5, ids(Tipo.values(), 5));
        regenerarYComparar(tmp.resolve("e"), EJEMPLOS, SEMILLA_EJEMPLOS, 2, ids(Tipo.values(), 2));
        for (Tipo t : Tipo.values()) {
            for (int i = 1; i <= 2; i++) {
                String id = t.prefijo() + "-0" + i;
                assertThat(Files.readAllBytes(EJEMPLOS.resolve(id + ".txt")))
                    .isEqualTo(Files.readAllBytes(tmp.resolve("e").resolve(id + ".txt")));
            }
        }
    }

    @Test
    void ejemplosFewShotReferenciadosPorElYamlExisten() throws IOException {
        String yaml;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("tipologias/embargos.v1.yaml")) {
            yaml = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        Matcher m = Pattern.compile("tools/synthetic-oficios/(ejemplos/[a-z]{2}-\\d{2}\\.txt)").matcher(yaml);
        Set<String> referenciados = new HashSet<>();
        while (m.find()) {
            referenciados.add(m.group(1));
        }
        assertThat(referenciados).hasSize(4);
        for (String ref : referenciados) {
            Path txt = Path.of(ref);
            assertThat(txt).isNotEmptyFile();
            assertThat(Files.readString(txt)).contains("--- TEXTO DEL OFICIO ---").contains("--- SALIDA ESPERADA (JSON) ---");
            String id = txt.getFileName().toString().replace(".txt", "");
            for (String f : new String[] {"oficio.pdf", "texto.txt", "verdad.json"}) {
                assertThat(EJEMPLOS.resolve(id).resolve(f)).as(id + "/" + f).isNotEmptyFile();
            }
        }
    }

    @Test
    void pdfsEscaneadosDelGoldenSetNoTienenTextoYSeRasterizan() throws IOException {
        for (JsonNode o : JSON.readTree(GOLDEN.resolve("manifest.json").toFile()).get("oficios")) {
            Path scan = GOLDEN.resolve(o.get("id").asText()).resolve("oficio-escaneado.pdf");
            try (PDDocument d = Loader.loadPDF(scan.toFile())) {
                assertThat(new PDFTextStripper().getText(d).strip()).isEmpty();
                assertThat(new PDFRenderer(d).renderImageWithDPI(0, 100, ImageType.RGB).getWidth()).isPositive();
            }
        }
    }

    private static List<String> ids(Tipo[] tipos, int n) {
        List<String> ids = new ArrayList<>();
        for (Tipo t : tipos) {
            for (int i = 1; i <= n; i++) {
                ids.add(t.prefijo() + "-0" + i);
            }
        }
        return ids;
    }

    private static void regenerarYComparar(Path salida, Path versionado, long semilla, int cantidad, List<String> ids)
        throws IOException {
        new BatchGenerator().generar(new Opciones(salida, semilla, cantidad, 1, List.of(Tipo.values()), Modo.NATIVO,
            Plan.CICLICO, true));
        for (String id : ids) {
            for (String f : new String[] {"verdad.json", "oficio.pdf", "texto.txt"}) {
                assertThat(Files.readAllBytes(versionado.resolve(id).resolve(f))).as(id + "/" + f)
                    .isEqualTo(Files.readAllBytes(salida.resolve(id).resolve(f)));
            }
        }
    }
}
