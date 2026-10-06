package com.idp.synthetic;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.geom.AffineTransform;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.DataBufferInt;
import java.awt.image.Kernel;
import java.io.IOException;
import java.util.Random;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.graphics.image.JPEGFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;

/**
 * Convierte un PDF nativo en un PDF solo-imagen que imita un escaneo: rotacion leve, desenfoque, manchas,
 * pliegue, ruido y motas. Determinista para una semilla dada (mismo renderizador y JVM).
 */
final class ScanSimulator {

    static final int DPI = 100;
    private static final float CALIDAD_JPEG = 0.5f;

    private ScanSimulator() {
    }

    /** PDF sin capa de texto con una imagen por pagina. El llamador cierra el resultado. */
    static PDDocument escanear(PDDocument origen, long semilla) throws IOException {
        Random rng = new Random(semilla);
        PDFRenderer renderer = new PDFRenderer(origen);
        PDDocument salida = new PDDocument();
        try {
            for (int i = 0; i < origen.getNumberOfPages(); i++) {
                BufferedImage pagina = renderer.renderImageWithDPI(i, DPI, ImageType.RGB);
                BufferedImage degradada = degradar(pagina, rng);
                PDRectangle caja = origen.getPage(i).getMediaBox();
                PDPage nueva = new PDPage(caja);
                salida.addPage(nueva);
                PDImageXObject imagen = JPEGFactory.createFromImage(salida, degradada, CALIDAD_JPEG, DPI);
                try (PDPageContentStream cs = new PDPageContentStream(salida, nueva)) {
                    cs.drawImage(imagen, 0, 0, caja.getWidth(), caja.getHeight());
                }
            }
            OficioPdfWriter.fijarIdentificador(salida, "escaneo/" + semilla);
            return salida;
        } catch (IOException | RuntimeException e) {
            salida.close();
            throw e;
        }
    }

    static BufferedImage degradar(BufferedImage src, Random rng) {
        int w = src.getWidth();
        int h = src.getHeight();
        BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int papel = 232 + rng.nextInt(16);
            g.setColor(new Color(papel, papel - 2, papel - 8));
            g.fillRect(0, 0, w, h);

            double grados = rng.nextDouble() * 3.0 - 1.5;
            AffineTransform t = new AffineTransform();
            t.translate(rng.nextInt(9) - 4, rng.nextInt(9) - 4);
            t.rotate(Math.toRadians(grados), w / 2.0, h / 2.0);
            g.drawImage(suavizar(src), t, null);

            int manchas = 2 + rng.nextInt(3);
            for (int i = 0; i < manchas; i++) {
                float cx = rng.nextInt(w);
                float cy = rng.nextInt(h);
                float r = 25 + rng.nextInt(70);
                g.setPaint(new RadialGradientPaint(cx, cy, r, new float[] {0f, 1f},
                    new Color[] {new Color(120, 95, 60, 70), new Color(120, 95, 60, 0)}));
                g.fillRect((int) (cx - r), (int) (cy - r), (int) (2 * r), (int) (2 * r));
            }
            g.setPaint(new Color(0, 0, 0, 22));
            g.fillRect(0, h / 3 + rng.nextInt(20), w, 2);

            g.setPaint(new Color(40, 40, 40, 120));
            int motas = 150 + rng.nextInt(250);
            for (int i = 0; i < motas; i++) {
                int d = 1 + rng.nextInt(2);
                g.fillOval(rng.nextInt(w), rng.nextInt(h), d, d);
            }
        } finally {
            g.dispose();
        }
        ruido(dst, rng, 6.0);
        return dst;
    }

    private static BufferedImage suavizar(BufferedImage src) {
        float[] k = {1f / 16, 2f / 16, 1f / 16, 2f / 16, 4f / 16, 2f / 16, 1f / 16, 2f / 16, 1f / 16};
        return new ConvolveOp(new Kernel(3, 3, k), ConvolveOp.EDGE_NO_OP, null).filter(src, null);
    }

    private static void ruido(BufferedImage img, Random rng, double sigma) {
        int[] px = ((DataBufferInt) img.getRaster().getDataBuffer()).getData();
        for (int i = 0; i < px.length; i++) {
            int d = (int) Math.round(rng.nextGaussian() * sigma);
            int p = px[i];
            int r = clamp(((p >> 16) & 0xFF) + d);
            int gg = clamp(((p >> 8) & 0xFF) + d);
            int b = clamp((p & 0xFF) + d);
            px[i] = (r << 16) | (gg << 8) | b;
        }
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(255, v));
    }
}
