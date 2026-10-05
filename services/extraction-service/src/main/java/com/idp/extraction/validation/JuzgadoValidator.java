package com.idp.extraction.validation;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Fuzzy matching (> 0.9, similitud de Levenshtein normalizada) contra el catalogo en memoria de
 * despachos de la rama judicial. Ignora numeros, ordinales y la ciudad/sufijo que sigue al despacho.
 */
public final class JuzgadoValidator implements FieldValidator {

    public static final double THRESHOLD = 0.9;
    private static final int MAX_INPUT = 200;
    private static final String CATALOG_RESOURCE = "catalogo/juzgados.txt";
    private static final Set<String> NOISE = Set.of(
        "no", "n", "num", "numero", "nro", "primero", "segundo", "tercero", "cuarto", "quinto", "sexto",
        "septimo", "octavo", "noveno", "decimo", "undecimo", "duodecimo", "vigesimo", "trigesimo",
        "primera", "segunda", "tercera", "cuarta", "quinta", "sexta", "septima", "octava", "novena");

    private final List<String> catalog;

    public JuzgadoValidator(List<String> catalogEntries) {
        List<String> normalized = new ArrayList<>();
        for (String e : catalogEntries) {
            String n = core(e);
            if (!n.isEmpty()) {
                normalized.add(n);
            }
        }
        this.catalog = List.copyOf(normalized);
    }

    /** Catalogo base empaquetado como recurso. */
    public static JuzgadoValidator withDefaultCatalog() {
        InputStream in = JuzgadoValidator.class.getClassLoader().getResourceAsStream(CATALOG_RESOURCE);
        if (in == null) {
            throw new IllegalStateException("Falta el recurso " + CATALOG_RESOURCE);
        }
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = r.readLine()) != null) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
            return new JuzgadoValidator(lines);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @Override
    public String id() {
        return "juzgado_catalogo";
    }

    @Override
    public ValidationResult validate(String value, ValidationContext ctx) {
        if (value == null || value.isBlank()) {
            return ValidationResult.fail("JUZGADO_VACIO", "Juzgado ausente");
        }
        if (value.length() > MAX_INPUT) {
            return ValidationResult.fail("JUZGADO_LONGITUD_EXCEDIDA", "Entrada excede el limite permitido");
        }
        String[] tokens = core(value).split(" ");
        double best = 0;
        for (int end = tokens.length; end >= Math.min(3, tokens.length); end--) {
            String candidate = String.join(" ", List.of(tokens).subList(0, end));
            for (String entry : catalog) {
                best = Math.max(best, similarity(candidate, entry));
            }
        }
        return best > THRESHOLD
            ? ValidationResult.pass()
            : ValidationResult.fail("JUZGADO_NO_CATALOGADO", "Despacho no encontrado en el catalogo");
    }

    static String core(String raw) {
        String n = Normalizer.normalize(raw.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replaceAll("[^a-z0-9]+", " ")
            .trim();
        StringBuilder sb = new StringBuilder();
        for (String t : n.split(" ")) {
            if (t.isEmpty() || NOISE.contains(t) || t.chars().anyMatch(Character::isDigit)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(t);
        }
        return sb.toString();
    }

    static double similarity(String a, String b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return 1.0 - (double) prev[b.length()] / Math.max(a.length(), b.length());
    }
}
