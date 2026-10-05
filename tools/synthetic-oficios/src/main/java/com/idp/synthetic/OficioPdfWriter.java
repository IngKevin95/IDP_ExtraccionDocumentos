package com.idp.synthetic;

import com.idp.synthetic.OficioComposer.Bloque;
import com.idp.synthetic.OficioComposer.Espacio;
import com.idp.synthetic.OficioComposer.Parrafo;
import com.idp.synthetic.OficioComposer.Tabla;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import org.apache.pdfbox.cos.COSArray;
import org.apache.pdfbox.cos.COSName;
import org.apache.pdfbox.cos.COSString;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.util.Matrix;

/** Escribe el oficio como PDF nativo (con capa de texto) y le agrega sellos para la variante escaneada. */
final class OficioPdfWriter {

    private static final float MARGEN = 56f;
    private static final PDRectangle PAGINA = PDRectangle.A4;

    private final PDFont normal = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
    private final PDFont negrita = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);

    private PDDocument doc;
    private PDPageContentStream cs;
    private float y;
    private int numeroPagina;
    private String pie;

    /** Documento PDF nativo en memoria; el llamador lo cierra. */
    PDDocument escribir(OficioData oficio) throws IOException {
        doc = new PDDocument();
        pie = "DOCUMENTO SINTÉTICO DE PRUEBA - SIN VALOR LEGAL - " + oficio.id();
        numeroPagina = 0;
        PDDocumentInformation info = new PDDocumentInformation();
        info.setTitle("Oficio sintético " + oficio.id());
        doc.setDocumentInformation(info);
        try {
            nuevaPagina();
            for (Bloque b : OficioComposer.componer(oficio)) {
                switch (b) {
                    case Parrafo p -> parrafo(p);
                    case Tabla t -> tabla(t);
                    case Espacio e -> y -= e.alto();
                }
            }
            cs.close();
            cs = null;
            fijarIdentificador(doc, oficio.id() + "/" + oficio.semilla());
            return doc;
        } catch (IOException | RuntimeException e) {
            if (cs != null) {
                cs.close();
            }
            doc.close();
            throw e;
        }
    }

    /** Serializa el documento (sin fechas ni ID aleatorio: mismos bytes para la misma entrada). */
    static byte[] bytes(PDDocument d) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        d.save(out);
        return out.toByteArray();
    }

    /** Texto de la capa nativa, con saltos de linea LF. */
    static String texto(PDDocument d) throws IOException {
        PDFTextStripper stripper = new PDFTextStripper();
        stripper.setSortByPosition(true);
        stripper.setLineSeparator("\n");
        stripper.setParagraphEnd("\n");
        stripper.setPageEnd("\n");
        return stripper.getText(d);
    }

    /** Fija el ID del trailer a partir del id del oficio: PDFBox lo generaria con la hora actual. */
    static void fijarIdentificador(PDDocument d, String clave) {
        try {
            byte[] h = MessageDigest.getInstance("MD5").digest(
                clave.getBytes(StandardCharsets.UTF_8));
            COSArray ids = new COSArray();
            ids.add(new COSString(h));
            ids.add(new COSString(h));
            d.getDocument().getTrailer().setItem(COSName.ID, ids);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private void nuevaPagina() throws IOException {
        if (cs != null) {
            cs.close();
        }
        PDPage page = new PDPage(PAGINA);
        doc.addPage(page);
        cs = new PDPageContentStream(doc, page);
        numeroPagina++;
        y = PAGINA.getHeight() - MARGEN;
        escribirLinea(normal, 7, MARGEN, 30, pie + " - pág. " + numeroPagina);
    }

    private void asegurar(float alto) throws IOException {
        if (y - alto < MARGEN) {
            nuevaPagina();
        }
    }

    private void parrafo(Parrafo p) throws IOException {
        PDFont f = p.negrita() ? negrita : normal;
        float interlineado = p.tam() * 1.4f;
        for (String linea : ajustar(p.texto(), f, p.tam(), PAGINA.getWidth() - 2 * MARGEN)) {
            asegurar(interlineado);
            float x = MARGEN;
            if (p.centrado()) {
                x = (PAGINA.getWidth() - ancho(f, p.tam(), linea)) / 2;
            }
            escribirLinea(f, p.tam(), x, y - p.tam(), linea);
            y -= interlineado;
        }
    }

    private void tabla(Tabla t) throws IOException {
        float tam = 8.5f;
        float interlineado = tam * 1.3f;
        String[] cabecera = t.filas().get(0);
        for (int r = 0; r < t.filas().size(); r++) {
            String[] fila = t.filas().get(r);
            int maxLineas = 1;
            for (int c = 0; c < fila.length; c++) {
                maxLineas = Math.max(maxLineas,
                    ajustar(fila[c], r == 0 ? negrita : normal, tam, t.anchos()[c] - 6).size());
            }
            float alto = maxLineas * interlineado + 6;
            if (y - alto < MARGEN) {
                nuevaPagina();
                if (r > 0) {
                    filaTabla(cabecera, t.anchos(), tam, interlineado, true);
                }
            }
            filaTabla(fila, t.anchos(), tam, interlineado, r == 0);
        }
    }

    private void filaTabla(String[] fila, float[] anchos, float tam, float interlineado, boolean cabecera)
        throws IOException {
        PDFont f = cabecera ? negrita : normal;
        List<List<String>> celdas = new ArrayList<>();
        int maxLineas = 1;
        for (int c = 0; c < fila.length; c++) {
            List<String> l = ajustar(fila[c], f, tam, anchos[c] - 6);
            celdas.add(l);
            maxLineas = Math.max(maxLineas, l.size());
        }
        float alto = maxLineas * interlineado + 6;
        float x = MARGEN;
        if (cabecera) {
            cs.setNonStrokingColor(0.9f, 0.9f, 0.9f);
            float total = 0;
            for (float a : anchos) {
                total += a;
            }
            cs.addRect(x, y - alto, total, alto);
            cs.fill();
            cs.setNonStrokingColor(0f, 0f, 0f);
        }
        cs.setStrokingColor(0f, 0f, 0f);
        cs.setLineWidth(0.5f);
        for (int c = 0; c < fila.length; c++) {
            cs.addRect(x, y - alto, anchos[c], alto);
            cs.stroke();
            float ty = y - 3 - tam;
            for (String linea : celdas.get(c)) {
                escribirLinea(f, tam, x + 3, ty, linea);
                ty -= interlineado;
            }
            x += anchos[c];
        }
        y -= alto;
    }

    private void escribirLinea(PDFont f, float tam, float x, float ty, String texto) throws IOException {
        cs.beginText();
        cs.setFont(f, tam);
        cs.newLineAtOffset(x, ty);
        cs.showText(texto);
        cs.endText();
    }

    private static float ancho(PDFont f, float tam, String s) throws IOException {
        return f.getStringWidth(s) / 1000f * tam;
    }

    private static List<String> ajustar(String texto, PDFont f, float tam, float max) throws IOException {
        List<String> lineas = new ArrayList<>();
        StringBuilder actual = new StringBuilder();
        for (String palabra : texto.split(" ")) {
            String candidata = actual.length() == 0 ? palabra : actual + " " + palabra;
            if (actual.length() > 0 && ancho(f, tam, candidata) > max) {
                lineas.add(actual.toString());
                actual = new StringBuilder(palabra);
            } else {
                actual = new StringBuilder(candidata);
            }
        }
        lineas.add(actual.toString());
        return lineas;
    }

    /** Sellos de recibido, sello circular y firma manuscrita aproximada (solo variante escaneada). */
    void decorarEscaneo(PDDocument d, Random rng, String fechaRecepcion) throws IOException {
        for (int i = 0; i < d.getNumberOfPages(); i++) {
            PDPage page = d.getPage(i);
            try (PDPageContentStream c = new PDPageContentStream(d, page, AppendMode.APPEND, true, true)) {
                PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
                gs.setStrokingAlphaConstant(0.6f);
                gs.setNonStrokingAlphaConstant(0.6f);
                c.setGraphicsStateParameters(gs);
                if (i == 0) {
                    selloRecibido(c, rng, fechaRecepcion);
                }
                selloCircular(c, rng);
                if (i == d.getNumberOfPages() - 1) {
                    firma(c, rng);
                }
            }
        }
    }

    private void selloRecibido(PDPageContentStream c, Random rng, String fecha) throws IOException {
        float x = 330 + rng.nextInt(120);
        float yy = 640 + rng.nextInt(90);
        double ang = Math.toRadians(-12 + rng.nextInt(24));
        c.saveGraphicsState();
        c.transform(Matrix.getRotateInstance(ang, x, yy));
        c.setStrokingColor(0.1f, 0.2f, 0.7f);
        c.setNonStrokingColor(0.1f, 0.2f, 0.7f);
        c.setLineWidth(2f);
        c.addRect(x, yy, 150, 50);
        c.stroke();
        escribirEn(c, negrita, 16, x + 30, yy + 30, "RECIBIDO");
        escribirEn(c, normal, 9, x + 12, yy + 17, fecha + " VENTANILLA SINT.");
        escribirEn(c, normal, 7, x + 12, yy + 6, "RADICACION AUTOMATICA DE PRUEBA");
        c.restoreGraphicsState();
    }

    private void selloCircular(PDPageContentStream c, Random rng) throws IOException {
        float cx = 120 + rng.nextInt(140);
        float cy = 90 + rng.nextInt(110);
        float r = 38;
        c.saveGraphicsState();
        c.setStrokingColor(0.75f, 0.1f, 0.1f);
        c.setNonStrokingColor(0.75f, 0.1f, 0.1f);
        c.setLineWidth(1.6f);
        circulo(c, cx, cy, r);
        c.stroke();
        circulo(c, cx, cy, r - 6);
        c.stroke();
        escribirEn(c, negrita, 8, cx - 28, cy + 4, "SELLO SINTETICO");
        escribirEn(c, normal, 7, cx - 17, cy - 8, "SIN VALOR");
        c.restoreGraphicsState();
    }

    private static void circulo(PDPageContentStream c, float cx, float cy, float r) throws IOException {
        float k = 0.5523f * r;
        c.moveTo(cx - r, cy);
        c.curveTo(cx - r, cy + k, cx - k, cy + r, cx, cy + r);
        c.curveTo(cx + k, cy + r, cx + r, cy + k, cx + r, cy);
        c.curveTo(cx + r, cy - k, cx + k, cy - r, cx, cy - r);
        c.curveTo(cx - k, cy - r, cx - r, cy - k, cx - r, cy);
        c.closePath();
    }

    private static void firma(PDPageContentStream c, Random rng) throws IOException {
        float x = 70 + rng.nextInt(40);
        float yy = 150 + rng.nextInt(60);
        c.saveGraphicsState();
        c.setStrokingColor(0.05f, 0.1f, 0.45f);
        c.setLineWidth(1.2f);
        c.moveTo(x, yy);
        for (int i = 1; i <= 12; i++) {
            c.curveTo(x + i * 6 - 4, yy + 10 + rng.nextInt(14), x + i * 6 - 2, yy - 10 - rng.nextInt(12),
                x + i * 6, yy + rng.nextInt(8) - 4);
        }
        c.stroke();
        c.restoreGraphicsState();
    }

    private static void escribirEn(PDPageContentStream c, PDFont f, float tam, float x, float yy, String s)
        throws IOException {
        c.beginText();
        c.setFont(f, tam);
        c.newLineAtOffset(x, yy);
        c.showText(s);
        c.endText();
    }
}
