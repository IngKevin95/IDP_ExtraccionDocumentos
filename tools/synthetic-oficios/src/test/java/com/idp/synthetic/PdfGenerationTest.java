package com.idp.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.synthetic.OficioGenerator.Plan;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class PdfGenerationTest {

    private static final OficioGenerator GEN = new OficioGenerator();

    @BeforeAll
    static void headless() {
        System.setProperty("java.awt.headless", "true");
    }

    private static byte[] nativo(OficioData o) throws IOException {
        try (PDDocument d = new OficioPdfWriter().escribir(o)) {
            return OficioPdfWriter.bytes(d);
        }
    }

    private static byte[] escaneado(OficioData o) throws IOException {
        OficioPdfWriter w = new OficioPdfWriter();
        try (PDDocument sellado = w.escribir(o)) {
            w.decorarEscaneo(sellado, new java.util.Random(1), "2025-01-01");
            try (PDDocument esc = ScanSimulator.escanear(sellado, 99)) {
                return OficioPdfWriter.bytes(esc);
            }
        }
    }

    @Test
    void pdfNativoEsDeterministaPorSemillaYTieneCapaDeTexto() throws IOException {
        for (Tipo t : Tipo.values()) {
            OficioData o = GEN.generar(t, 42, 1, Plan.LIMPIO);
            byte[] a = nativo(o);
            assertThat(nativo(GEN.generar(t, 42, 1, Plan.LIMPIO))).isEqualTo(a);
            assertThat(nativo(GEN.generar(t, 43, 1, Plan.LIMPIO))).isNotEqualTo(a);
            try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(a)) {
                String texto = new PDFTextStripper().getText(d);
                String plano = texto.replaceAll("\\s+", " ");
                assertThat(plano).contains(o.campo("radicado")).contains(o.campo("tipo_medida"))
                    .contains(o.campo("monto_letras")).contains("SIN VALOR LEGAL");
                for (Map<String, String> fila : o.demandados()) {
                    assertThat(texto).contains(fila.get("numero_identificacion"));
                }
            }
        }
    }

    @Test
    void pdfNativoMarcaSinteticoEnElCuerpoYEnLosMetadatos() throws IOException {
        for (Tipo t : Tipo.values()) {
            byte[] pdf = nativo(GEN.generar(t, 42, 1, Plan.LIMPIO));
            try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                String texto = new PDFTextStripper().getText(d);
                String sinPie = texto.lines().filter(l -> !l.contains("DOCUMENTO SINTÉTICO DE PRUEBA"))
                    .collect(java.util.stream.Collectors.joining("\n"));
                assertThat(sinPie).contains(OficioPdfWriter.MARCA_CUERPO);
                assertThat(texto.lines().filter(l -> l.contains("DOCUMENTO SINTÉTICO DE PRUEBA")).count())
                    .isEqualTo(d.getNumberOfPages());
                var info = d.getDocumentInformation();
                assertThat(info.getSubject()).contains("SINTÉTICO").contains("SIN VALOR LEGAL");
                assertThat(info.getKeywords()).contains("ficticio");
                assertThat(info.getCustomMetadataValue("Sintetico")).isEqualTo("true");
            }
        }
    }

    @Test
    void pdfNativoSeRasterizaSinErrorYNoEstaEnBlanco() throws IOException {
        for (Tipo t : Tipo.values()) {
            byte[] pdf = nativo(GEN.generar(t, 42, 2, Plan.CICLICO));
            try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(pdf)) {
                assertThat(d.getNumberOfPages()).isPositive();
                for (int p = 0; p < d.getNumberOfPages(); p++) {
                    BufferedImage img = new PDFRenderer(d).renderImageWithDPI(p, 150, ImageType.RGB);
                    assertThat(img.getWidth()).isGreaterThan(1000);
                    assertThat(oscuros(img)).isGreaterThan(500);
                }
            }
        }
    }

    @Test
    void tablaLargaPaginaYRepiteCabecera() throws IOException {
        OficioData base = GEN.generar(Tipo.EJ, 77, 1, Plan.LIMPIO);
        java.util.List<Map<String, String>> muchos = new java.util.ArrayList<>();
        for (int i = 0; i < 60; i++) {
            muchos.add(base.demandados().get(i % base.demandados().size()));
        }
        OficioData largo = new OficioData(base.id(), base.tipo(), base.semilla(), base.indice(), base.numeroOficio(),
            base.campos(), muchos, base.defectos(), base.inyeccion(), base.sumaFilas());
        try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(nativo(largo))) {
            assertThat(d.getNumberOfPages()).isGreaterThan(1);
            PDFTextStripper s = new PDFTextStripper();
            s.setStartPage(2);
            s.setEndPage(2);
            assertThat(s.getText(d)).contains("Tipo ID").contains("pág. 2");
        }
    }

    @Test
    void pdfEscaneadoNoTieneTextoSeRasterizaYEsDeterminista() throws IOException {
        for (Tipo t : Tipo.values()) {
            OficioData o = GEN.generar(t, 42, 3, Plan.CICLICO);
            byte[] a = escaneado(o);
            assertThat(escaneado(o)).isEqualTo(a);
            try (PDDocument d = org.apache.pdfbox.Loader.loadPDF(a)) {
                assertThat(new PDFTextStripper().getText(d).strip()).isEmpty();
                BufferedImage img = new PDFRenderer(d).renderImageWithDPI(0, 100, ImageType.RGB);
                assertThat(oscuros(img)).isGreaterThan(500);
            }
            assertThat(a.length).isLessThan(1_500_000);
        }
    }

    private static int oscuros(BufferedImage img) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y += 2) {
            for (int x = 0; x < img.getWidth(); x += 2) {
                int rgb = img.getRGB(x, y);
                int lum = (((rgb >> 16) & 0xFF) + ((rgb >> 8) & 0xFF) + (rgb & 0xFF)) / 3;
                if (lum < 100) {
                    n++;
                }
            }
        }
        return n;
    }
}
