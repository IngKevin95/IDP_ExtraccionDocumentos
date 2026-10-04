package com.idp.renderer.security;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.core.RenderException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.springframework.stereotype.Component;

/**
 * Valida el contenedor DOCX sin parsear XML (evita XXE): estructura, zip-bomb (bytes descomprimidos reales
 * acotados) y contenido activo (macros VBA, ActiveX, objetos OLE embebidos).
 */
@Component
public class DocxInspector {

    private static final int MAX_ENTRIES = 10_000;
    private static final int MAX_CONTENT_TYPES_BYTES = 1 << 20;

    private final long maxUncompressed;

    public DocxInspector(RendererProperties props) {
        this.maxUncompressed = props.limits().maxUncompressedBytes();
    }

    public void inspect(Path file) {
        boolean contentTypes = false;
        boolean document = false;
        boolean macroContentType = false;
        long total = 0;
        int count = 0;
        try (ZipFile zip = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry e = entries.nextElement();
                if (++count > MAX_ENTRIES) {
                    throw RenderException.limitExceeded("El contenedor tiene demasiadas entradas.");
                }
                String name = e.getName().toLowerCase(Locale.ROOT);
                if (name.endsWith("vbaproject.bin") || name.endsWith("vbadata.xml")) {
                    throw RenderException.conversionRejected("El documento contiene macros.");
                }
                if (name.startsWith("word/activex/") || name.startsWith("word/embeddings/")) {
                    throw RenderException.activeContent("El documento contiene objetos embebidos.");
                }
                boolean isContentTypes = e.getName().equals("[Content_Types].xml");
                contentTypes |= isContentTypes;
                document |= e.getName().equals("word/document.xml");
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] buf = new byte[16 * 1024];
                    long entryBytes = 0;
                    StringBuilder head = isContentTypes ? new StringBuilder() : null;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        entryBytes += n;
                        if (total > maxUncompressed) {
                            throw RenderException.limitExceeded("El contenido descomprimido excede el limite.");
                        }
                        if (head != null && entryBytes <= MAX_CONTENT_TYPES_BYTES) {
                            head.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                        }
                    }
                    if (head != null) {
                        macroContentType = head.toString().toLowerCase(Locale.ROOT).contains("macroenabled");
                    }
                }
            }
        } catch (ZipException e) {
            throw RenderException.invalid("Archivo DOCX corrupto.");
        } catch (IOException e) {
            throw RenderException.internal("No se pudo inspeccionar el DOCX.");
        }
        if (!contentTypes || !document) {
            throw RenderException.unsupportedFormat("El contenedor ZIP no es un documento DOCX.");
        }
        if (macroContentType) {
            throw RenderException.conversionRejected("El documento habilita macros.");
        }
    }
}
