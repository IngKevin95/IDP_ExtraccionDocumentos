package com.idp.chat.service;

import com.idp.chat.domain.Chunk;
import com.idp.chat.domain.Citation;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Parsea las citas {@code [chunk:<id>] "<cita>"} de la salida del LLM y las verifica contra los fragmentos que se le
 * entregaron (SEC-031): el chunk debe pertenecer a los recuperados y la cita debe estar contenida literalmente en su
 * contenido (comparacion tolerante solo a espacios en blanco). Ademas (SEC-048) el texto libre, ya sin marcadores y
 * saneado, no puede afirmar cifras, montos (en digitos o en letras), fechas ni monedas que no aparezcan (tras
 * normalizar formato) en alguna cita valida o en el fragmento citado: una cita trivial no avala hechos inventados.
 */
@Component
public class GroundingVerifier {

    /** Cita minima aceptable (~3 palabras): evita "citas" triviales que cualquier fragmento contiene. */
    static final int MIN_QUOTE_CHARS = 16;
    static final int MAX_QUOTE_CHARS = 1000;

    private static final String UUID_RE = "[0-9a-fA-F]{8}-(?:[0-9a-fA-F]{4}-){3}[0-9a-fA-F]{12}";
    private static final Pattern MARKER_START = Pattern.compile("(?i)\\[\\s*chunk\\s*:");
    private static final Pattern CITATION = Pattern.compile("\\[\\s*chunk\\s*:\\s*(" + UUID_RE + ")\\s*\\]\\s*"
            + "(?:\"((?:[^\"\\\\]|\\\\.){1," + MAX_QUOTE_CHARS + "})\"|“([^”]{1," + MAX_QUOTE_CHARS
            + "})”)", Pattern.CASE_INSENSITIVE);
    private static final Pattern MARKER_N = Pattern.compile("\\[(\\d{1,2})\\]");
    private static final Pattern WS = Pattern.compile("\\s+");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");

    /**
     * @param text sin marcadores de cita: cada cita valida se sustituye por {@code [n]} (n = posicion en citations)
     * @param citations citas verificadas, sin duplicados
     * @param invalid citas mal formadas, de un fragmento no recuperado o cuyo texto no esta en el fragmento
     * @param abstention el texto declara informacion insuficiente
     * @param ungrounded hechos (cifras, fechas, monedas) del texto libre ausentes de las citas y fragmentos citados
     */
    public record Result(String text, List<Citation> citations, int invalid, boolean abstention, int ungrounded) {
    }

    public Result verify(String llmOutput, List<Chunk> retrieved) {
        Map<UUID, Chunk> byId = new HashMap<>();
        for (Chunk c : retrieved) {
            byId.put(c.id(), c);
        }
        Matcher m = CITATION.matcher(llmOutput);
        List<Citation> valid = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        int last = 0;
        int matches = 0;
        int invalid = 0;
        while (m.find()) {
            matches++;
            text.append(llmOutput, last, m.start());
            last = m.end();
            String quote = m.group(2) != null ? m.group(2).replace("\\\"", "\"").replace("\\\\", "\\") : m.group(3);
            Citation c = check(m.group(1), quote, byId);
            if (c == null) {
                invalid++;
                continue;
            }
            int idx = valid.indexOf(c);
            if (idx < 0) {
                valid.add(c);
                idx = valid.size() - 1;
            }
            text.append('[').append(idx + 1).append(']');
        }
        text.append(llmOutput, last, llmOutput.length());
        int markers = 0;
        Matcher s = MARKER_START.matcher(llmOutput);
        while (s.find()) {
            markers++;
        }
        invalid += Math.max(0, markers - matches);
        List<String> sources = new ArrayList<>();
        for (Citation c : valid) {
            sources.add(c.exactQuote());
            sources.add(byId.get(c.chunkId()).content());
        }
        String clean = WS.matcher(OutputSanitizer.sanitize(text.toString(), OutputSanitizer.urlsIn(sources)))
                .replaceAll(" ").strip();
        return new Result(clean, valid, invalid, isAbstention(llmOutput), ungrounded(clean, valid.size(), sources));
    }

    /** Cuenta los hechos del texto libre (sin marcadores [n]) que no figuran en las fuentes citadas. */
    private static int ungrounded(String clean, int citations, List<String> sources) {
        String free = MARKER_N.matcher(clean).replaceAll(m -> Integer.parseInt(m.group(1)) <= citations
                ? " " : Matcher.quoteReplacement(m.group()));
        Set<String> facts = FactTokens.extract(free);
        if (facts.isEmpty()) {
            return 0;
        }
        Set<String> known = new HashSet<>();
        for (String src : sources) {
            known.addAll(FactTokens.extract(src));
        }
        facts.removeAll(known);
        return facts.size();
    }

    private static Citation check(String chunkId, String quote, Map<UUID, Chunk> byId) {
        UUID id;
        try {
            id = UUID.fromString(chunkId);
        } catch (IllegalArgumentException e) {
            return null;
        }
        Chunk chunk = byId.get(id);
        if (chunk == null) {
            return null;
        }
        String normalizedQuote = WS.matcher(quote).replaceAll(" ").strip();
        if (normalizedQuote.length() < MIN_QUOTE_CHARS) {
            return null;
        }
        if (chunk.content().contains(quote)) {
            return new Citation(id, chunk.pageNumber(), quote);
        }
        String normalizedContent = WS.matcher(chunk.content()).replaceAll(" ");
        if (normalizedContent.contains(normalizedQuote)) {
            return new Citation(id, chunk.pageNumber(), normalizedQuote);
        }
        return null;
    }

    static boolean isAbstention(String text) {
        String n = DIACRITICS.matcher(Normalizer.normalize(text, Normalizer.Form.NFD)).replaceAll("")
                .toLowerCase(Locale.ROOT);
        return n.contains("informacion insuficiente");
    }
}
