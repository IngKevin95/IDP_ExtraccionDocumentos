package com.idp.extraction.validation;

import java.text.Normalizer;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Convierte cantidades escritas en espanol ("cien mil pesos", "dos millones quinientos mil") a entero.
 * Soporta hasta billones. Ignora palabras de moneda ("pesos", "m/cte", "moneda corriente").
 */
public final class SpanishNumberWords {

    private static final int MAX_LENGTH = 400;

    private static final Map<String, Long> UNITS = Map.ofEntries(
        Map.entry("cero", 0L), Map.entry("un", 1L), Map.entry("uno", 1L), Map.entry("una", 1L),
        Map.entry("dos", 2L), Map.entry("tres", 3L), Map.entry("cuatro", 4L), Map.entry("cinco", 5L),
        Map.entry("seis", 6L), Map.entry("siete", 7L), Map.entry("ocho", 8L), Map.entry("nueve", 9L),
        Map.entry("diez", 10L), Map.entry("once", 11L), Map.entry("doce", 12L), Map.entry("trece", 13L),
        Map.entry("catorce", 14L), Map.entry("quince", 15L), Map.entry("dieciseis", 16L),
        Map.entry("diecisiete", 17L), Map.entry("dieciocho", 18L), Map.entry("diecinueve", 19L),
        Map.entry("veinte", 20L), Map.entry("veintiun", 21L), Map.entry("veintiuno", 21L),
        Map.entry("veintiuna", 21L), Map.entry("veintidos", 22L), Map.entry("veintitres", 23L),
        Map.entry("veinticuatro", 24L), Map.entry("veinticinco", 25L), Map.entry("veintiseis", 26L),
        Map.entry("veintisiete", 27L), Map.entry("veintiocho", 28L), Map.entry("veintinueve", 29L));

    private static final Map<String, Long> TENS = Map.of(
        "treinta", 30L, "cuarenta", 40L, "cincuenta", 50L, "sesenta", 60L,
        "setenta", 70L, "ochenta", 80L, "noventa", 90L);

    private static final Map<String, Long> HUNDREDS = Map.ofEntries(
        Map.entry("cien", 100L), Map.entry("ciento", 100L), Map.entry("doscientos", 200L),
        Map.entry("doscientas", 200L), Map.entry("trescientos", 300L), Map.entry("trescientas", 300L),
        Map.entry("cuatrocientos", 400L), Map.entry("cuatrocientas", 400L), Map.entry("quinientos", 500L),
        Map.entry("quinientas", 500L), Map.entry("seiscientos", 600L), Map.entry("seiscientas", 600L),
        Map.entry("setecientos", 700L), Map.entry("setecientas", 700L), Map.entry("ochocientos", 800L),
        Map.entry("ochocientas", 800L), Map.entry("novecientos", 900L), Map.entry("novecientas", 900L));

    private static final Set<String> IGNORED = Set.of(
        "pesos", "peso", "m/cte", "mcte", "m/l", "ml", "moneda", "corriente", "legal", "colombiana",
        "colombianos", "cop", "de", "y", "exactos", "00/100");

    private SpanishNumberWords() {
    }

    /** Valor entero del texto, o vacio si contiene palabras no numericas o esta mal formado. */
    public static Optional<Long> parse(String text) {
        if (text == null || text.isBlank() || text.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        String norm = Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replace("$", " ")
            .replace(",", " ")
            .replace(".", " ");
        String[] tokens = norm.trim().split("\\s+");
        long total = 0;
        long group = 0;
        long current = 0;
        boolean any = false;
        try {
            for (String t : tokens) {
                if (IGNORED.contains(t)) {
                    continue;
                }
                Long v;
                if ((v = UNITS.get(t)) != null || (v = TENS.get(t)) != null || (v = HUNDREDS.get(t)) != null) {
                    current = Math.addExact(current, v);
                    any = true;
                } else if (t.equals("mil")) {
                    long mult = current == 0 ? 1 : current;
                    group = Math.addExact(group, Math.multiplyExact(mult, 1_000L));
                    current = 0;
                    any = true;
                } else if (t.equals("millon") || t.equals("millones")) {
                    long mult = group + current == 0 ? 1 : group + current;
                    total = Math.addExact(total, Math.multiplyExact(mult, 1_000_000L));
                    group = 0;
                    current = 0;
                    any = true;
                } else if (t.equals("billon") || t.equals("billones")) {
                    long mult = group + current == 0 ? 1 : group + current;
                    total = Math.addExact(total, Math.multiplyExact(mult, 1_000_000_000_000L));
                    group = 0;
                    current = 0;
                    any = true;
                } else {
                    return Optional.empty();
                }
            }
        } catch (ArithmeticException e) {
            return Optional.empty();
        }
        return any ? Optional.of(Math.addExact(total, Math.addExact(group, current))) : Optional.empty();
    }
}
