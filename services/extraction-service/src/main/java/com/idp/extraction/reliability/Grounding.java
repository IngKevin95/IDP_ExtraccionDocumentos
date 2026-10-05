package com.idp.extraction.reliability;

import com.idp.extraction.core.ExtractedValue;
import com.idp.extraction.typology.FieldDef;
import com.idp.extraction.typology.FieldType;
import com.idp.extraction.validation.AmountParser;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.HashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Grounding hibrido inverso (ADR-0012): los campos criticos se emparejan con coincidencia EXACTA
 * tras normalizacion (sin distancia de edicion) contra la capa de texto nativa del documento.
 */
public final class Grounding {

    public enum Status { NOT_REQUIRED, NO_TEXT_LAYER, FOUND, NOT_FOUND }

    private static final Pattern NUMBER_TOKEN = Pattern.compile("\\d[\\d.,]{0,38}");

    public Status check(FieldDef def, ExtractedValue value, String nativeText) {
        if (!def.critico() || value == null || !value.present()) {
            return Status.NOT_REQUIRED;
        }
        if (nativeText == null || nativeText.isBlank()) {
            return Status.NO_TEXT_LAYER;
        }
        boolean found = def.type() == FieldType.DECIMAL
            ? containsAmount(value.value(), nativeText)
            : normalize(nativeText).contains(normalize(value.value()));
        return found ? Status.FOUND : Status.NOT_FOUND;
    }

    private static boolean containsAmount(String amount, String text) {
        Optional<BigDecimal> target = AmountParser.parse(amount);
        if (target.isEmpty()) {
            return false;
        }
        Set<BigDecimal> tokens = new HashSet<>();
        Matcher m = NUMBER_TOKEN.matcher(text);
        while (m.find()) {
            String t = m.group();
            while (!t.isEmpty() && (t.endsWith(".") || t.endsWith(","))) {
                t = t.substring(0, t.length() - 1);
            }
            AmountParser.parse(t).ifPresent(v -> tokens.add(v.stripTrailingZeros()));
        }
        return tokens.contains(target.get().stripTrailingZeros());
    }

    /** Minusculas, sin acentos y solo alfanumericos. */
    static String normalize(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("\\p{M}", "")
            .replaceAll("[^a-z0-9]", "");
    }
}
