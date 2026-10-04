package com.idp.renderer.core;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.security.PdfActiveContentGuard;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.io.IOUtils;
import org.apache.pdfbox.io.RandomAccessReadBufferedFile;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

/** Rasteriza PDF pagina a pagina y extrae la capa de texto nativa por pagina. */
@Component
public class PdfProcessor {

    private final RendererProperties.Limits limits;
    private final int dpi;

    public PdfProcessor(RendererProperties props) {
        this.limits = props.limits();
        this.dpi = props.raster().dpi();
    }

    public int process(Path pdf, ResultPackage out, Deadline deadline) throws IOException {
        try (PDDocument doc = load(pdf)) {
            int pages = doc.getNumberOfPages();
            if (pages == 0) {
                throw RenderException.invalid("PDF sin paginas.");
            }
            if (pages > limits.maxPages()) {
                throw RenderException.limitExceeded("El documento excede el maximo de paginas.");
            }
            PdfActiveContentGuard.check(doc);
            PDFRenderer renderer = new PDFRenderer(doc);
            renderer.setSubsamplingAllowed(true);
            PDFTextStripper stripper = new PDFTextStripper();
            float scale = dpi / 72f;
            for (int i = 0; i < pages; i++) {
                deadline.check();
                PDPage page = doc.getPage(i);
                PDRectangle box = page.getCropBox();
                if ((double) box.getWidth() * scale * box.getHeight() * scale > limits.maxPixelsPerPage()) {
                    throw RenderException.limitExceeded("Una pagina excede el tamano maximo de rasterizado.");
                }
                BufferedImage img = renderer.renderImageWithDPI(i, dpi, ImageType.RGB);
                stripper.setStartPage(i + 1);
                stripper.setEndPage(i + 1);
                out.addPage(img, stripper.getText(doc).strip());
                img.flush();
            }
            return pages;
        }
    }

    private static PDDocument load(Path pdf) throws IOException {
        try {
            return Loader.loadPDF(new RandomAccessReadBufferedFile(pdf.toFile()), "", null, null,
                    IOUtils.createTempFileOnlyStreamCache());
        } catch (InvalidPasswordException e) {
            throw RenderException.encrypted();
        } catch (IOException e) {
            throw RenderException.invalid("PDF corrupto o ilegible.");
        }
    }
}
