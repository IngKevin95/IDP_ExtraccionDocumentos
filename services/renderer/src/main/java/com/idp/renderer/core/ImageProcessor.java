package com.idp.renderer.core;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.security.DocType;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.springframework.stereotype.Component;

/** Imagenes JPG/PNG/TIFF: re-codifica a PNG (TIFF multipagina produce una pagina por frame), sin texto. */
@Component
public class ImageProcessor {

    private final RendererProperties.Limits limits;

    public ImageProcessor(RendererProperties props) {
        this.limits = props.limits();
    }

    public int process(Path file, DocType type, ResultPackage out, Deadline deadline) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(file.toFile())) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName(type.format());
            if (in == null || !readers.hasNext()) {
                throw RenderException.invalid("Imagen ilegible.");
            }
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, false, true);
                int frames = reader.getNumImages(true);
                if (frames <= 0) {
                    throw RenderException.invalid("Imagen sin contenido.");
                }
                if (frames > limits.maxPages()) {
                    throw RenderException.limitExceeded("La imagen excede el maximo de paginas.");
                }
                for (int i = 0; i < frames; i++) {
                    deadline.check();
                    if ((long) reader.getWidth(i) * reader.getHeight(i) > limits.maxPixelsPerPage()) {
                        throw RenderException.limitExceeded("La imagen excede el tamano maximo de rasterizado.");
                    }
                    BufferedImage img = reader.read(i);
                    out.addPage(img, "");
                    img.flush();
                }
                return frames;
            } catch (RenderException e) {
                throw e;
            } catch (IOException | RuntimeException e) {
                throw RenderException.invalid("Imagen corrupta o ilegible.");
            } finally {
                reader.dispose();
            }
        }
    }
}
