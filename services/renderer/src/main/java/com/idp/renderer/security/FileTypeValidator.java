package com.idp.renderer.security;

import com.idp.renderer.core.RenderException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.stereotype.Component;

/** Determina el tipo real por magic bytes, ignorando extension y content-type declarados. */
@Component
public class FileTypeValidator {

    public DocType detect(Path file) {
        byte[] h = new byte[8];
        int n;
        try (InputStream in = Files.newInputStream(file)) {
            n = in.readNBytes(h, 0, h.length);
        } catch (IOException e) {
            throw RenderException.internal("No se pudo leer el archivo.");
        }
        if (n >= 5 && h[0] == '%' && h[1] == 'P' && h[2] == 'D' && h[3] == 'F' && h[4] == '-') {
            return DocType.PDF;
        }
        if (n >= 8 && (h[0] & 0xFF) == 0x89 && h[1] == 'P' && h[2] == 'N' && h[3] == 'G'
                && h[4] == 0x0D && h[5] == 0x0A && h[6] == 0x1A && h[7] == 0x0A) {
            return DocType.PNG;
        }
        if (n >= 3 && (h[0] & 0xFF) == 0xFF && (h[1] & 0xFF) == 0xD8 && (h[2] & 0xFF) == 0xFF) {
            return DocType.JPEG;
        }
        if (n >= 4 && ((h[0] == 'I' && h[1] == 'I' && h[2] == 42 && h[3] == 0)
                || (h[0] == 'M' && h[1] == 'M' && h[2] == 0 && h[3] == 42))) {
            return DocType.TIFF;
        }
        if (n >= 4 && h[0] == 'P' && h[1] == 'K' && h[2] == 3 && h[3] == 4) {
            return DocType.DOCX; // contenedor ZIP; DocxInspector confirma que realmente es DOCX
        }
        throw RenderException.unsupportedFormat("Formato de archivo no soportado.");
    }
}
