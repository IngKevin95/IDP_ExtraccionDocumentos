package com.idp.renderer.security;

import com.idp.renderer.config.RendererProperties;
import com.idp.renderer.core.RenderException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    private static final int MAX_SCAN_BYTES = 32 << 20;
    private static final int FIELD_WINDOW = 600;
    private static final String[] REMOTE_MARKERS = {"http:", "https:", "ftp:", "file:", "\\\\", "//"};

    private static final Pattern TAG = Pattern.compile("<[^>]*>");
    private static final Pattern ATTR = Pattern.compile("([^\\s=/<>\"']+)\\s*=\\s*(\"[^\"]*\"|'[^']*')");
    private static final Pattern SCHEME = Pattern.compile("^[a-zA-Z][a-zA-Z0-9+.\\-]*:");
    private static final Pattern PREDEFINED_ONLY = Pattern.compile(
            "(?s)(?:[^&]|&(?:amp|lt|gt|quot|apos);)*");

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
                boolean rels = name.endsWith(".rels");
                boolean wordXml = name.startsWith("word/") && name.endsWith(".xml");
                boolean isContentTypes = e.getName().equals("[Content_Types].xml");
                contentTypes |= isContentTypes;
                document |= e.getName().equals("word/document.xml");
                try (InputStream in = zip.getInputStream(e)) {
                    byte[] buf = new byte[16 * 1024];
                    long entryBytes = 0;
                    StringBuilder head = isContentTypes ? new StringBuilder() : null;
                    java.io.ByteArrayOutputStream scan = rels || wordXml ? new java.io.ByteArrayOutputStream() : null;
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        total += n;
                        entryBytes += n;
                        if (total > maxUncompressed) {
                            throw RenderException.limitExceeded("El contenido descomprimido excede el limite.");
                        }
                        if (scan != null) {
                            if (entryBytes > MAX_SCAN_BYTES) {
                                throw RenderException.limitExceeded("Una parte del documento excede el limite.");
                            }
                            scan.write(buf, 0, n);
                        }
                        if (head != null && entryBytes <= MAX_CONTENT_TYPES_BYTES) {
                            head.append(new String(buf, 0, n, StandardCharsets.ISO_8859_1));
                        }
                    }
                    if (scan != null) {
                        byte[] raw = scan.toByteArray();
                        rejectOpaqueEncoding(raw);
                        String text = new String(raw, StandardCharsets.ISO_8859_1);
                        if (rels) {
                            rejectExternalTargets(text);
                        } else {
                            rejectAttributeEntities(text);
                            rejectRemoteFields(text);
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

    /** BOM UTF-16/32 o bytes NUL (UTF-16 sin BOM): codificaciones que evaden la inspeccion por tokens. */
    static void rejectOpaqueEncoding(byte[] raw) {
        if (raw.length >= 2 && ((raw[0] & 0xFF) == 0xFF && (raw[1] & 0xFF) == 0xFE
                || (raw[0] & 0xFF) == 0xFE && (raw[1] & 0xFF) == 0xFF)) {
            throw RenderException.activeContent("Codificacion de parte XML no permitida.");
        }
        for (byte b : raw) {
            if (b == 0) {
                throw RenderException.activeContent("Parte XML con bytes NUL no permitida.");
            }
        }
    }

    /**
     * Allowlist de relaciones: cada Target debe ser relativo (sin esquema, sin ruta absoluta o UNC) y no puede
     * existir TargetMode. Inspeccion por tokens, sin parsear XML; cualquier referencia de entidad se rechaza.
     */
    static void rejectExternalTargets(String rels) {
        rejectDoctype(rels);
        Matcher tags = TAG.matcher(rels);
        while (tags.find()) {
            String tag = tags.group();
            if (tag.indexOf('&') >= 0) {
                throw RenderException.activeContent("Referencias de entidad no permitidas en relaciones.");
            }
            String lower = tag.toLowerCase(Locale.ROOT);
            if (!lower.startsWith("<relationship") || lower.startsWith("<relationships")) {
                continue;
            }
            String target = null;
            Matcher attrs = ATTR.matcher(tag);
            while (attrs.find()) {
                String attr = attrs.group(1).toLowerCase(Locale.ROOT);
                if (attr.equals("targetmode")) {
                    throw RenderException.activeContent("El documento declara TargetMode.");
                }
                if (attr.equals("target")) {
                    target = attrs.group(2).substring(1, attrs.group(2).length() - 1);
                }
            }
            if (target == null || !isRelative(target)) {
                throw RenderException.activeContent("El documento referencia recursos externos.");
            }
        }
    }

    private static boolean isRelative(String target) {
        String t = target.strip();
        return !t.isEmpty() && !t.startsWith("/") && !t.startsWith("\\") && !t.contains("\\\\")
                && !SCHEME.matcher(t).find();
    }

    private static void rejectDoctype(String xml) {
        String lower = xml.toLowerCase(Locale.ROOT);
        if (lower.contains("<!doctype") || lower.contains("<!entity")) {
            throw RenderException.activeContent("DOCTYPE no permitido.");
        }
    }

    /** En word/*.xml se rechazan referencias de caracter o entidades no predefinidas dentro de etiquetas. */
    static void rejectAttributeEntities(String xml) {
        rejectDoctype(xml);
        Matcher tags = TAG.matcher(xml);
        while (tags.find()) {
            String tag = tags.group();
            if (tag.indexOf('&') >= 0 && !PREDEFINED_ONLY.matcher(tag).matches()) {
                throw RenderException.activeContent("Referencias de caracter no permitidas en atributos.");
            }
        }
    }

    /** Campos INCLUDEPICTURE/INCLUDETEXT con destino remoto; se quitan las etiquetas para vencer runs partidos. */
    static void rejectRemoteFields(String xml) {
        String text = stripTags(xml).toLowerCase(Locale.ROOT);
        for (String field : new String[] {"includepicture", "includetext"}) {
            int from = 0;
            int at;
            while ((at = text.indexOf(field, from)) >= 0) {
                String window = text.substring(at, Math.min(text.length(), at + FIELD_WINDOW));
                for (String marker : REMOTE_MARKERS) {
                    if (window.contains(marker)) {
                        throw RenderException.activeContent("El documento incluye contenido remoto.");
                    }
                }
                from = at + field.length();
            }
        }
    }

    private static String stripTags(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        boolean inTag = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '<') {
                inTag = true;
            } else if (c == '>') {
                inTag = false;
            } else if (!inTag) {
                sb.append(c);
            }
        }
        return sb.toString();
    }
}
