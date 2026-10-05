package com.idp.extraction.validation;

import java.math.BigDecimal;
import java.util.Optional;

/**
 * Interpreta montos numericos en notacion colombiana o anglosajona sin perder cifras:
 * {@code 10.000.000,50}, {@code 10,000,000.50}, {@code 10.000}, {@code $ 1500000}.
 */
public final class AmountParser {

    private static final int MAX_LENGTH = 40;

    private AmountParser() {
    }

    public static Optional<BigDecimal> parse(String raw) {
        if (raw == null || raw.length() > MAX_LENGTH) {
            return Optional.empty();
        }
        StringBuilder sb = new StringBuilder(raw.length());
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ((c >= '0' && c <= '9') || c == '.' || c == ',') {
                sb.append(c);
            } else if (c != '$' && c != ' ' && c != ' ') {
                return Optional.empty();
            }
        }
        String s = sb.toString();
        if (s.isEmpty() || s.charAt(0) == '.' || s.charAt(0) == ',') {
            return Optional.empty();
        }
        int lastDot = s.lastIndexOf('.');
        int lastComma = s.lastIndexOf(',');
        char decimalSep = 0;
        if (lastDot >= 0 && lastComma >= 0) {
            decimalSep = lastDot > lastComma ? '.' : ',';
        } else if (lastDot >= 0 || lastComma >= 0) {
            char sep = lastDot >= 0 ? '.' : ',';
            int last = Math.max(lastDot, lastComma);
            int occurrences = (int) s.chars().filter(ch -> ch == sep).count();
            int after = s.length() - last - 1;
            if (occurrences == 1 && after != 3) {
                decimalSep = sep;
            } else if (occurrences > 1 && after != 3) {
                return Optional.empty();
            }
        }
        String integerPart = s;
        String fraction = "";
        if (decimalSep != 0) {
            int idx = s.lastIndexOf(decimalSep);
            integerPart = s.substring(0, idx);
            fraction = s.substring(idx + 1);
        }
        integerPart = integerPart.replace(".", "").replace(",", "");
        if (integerPart.isEmpty() || fraction.contains(".") || fraction.contains(",")) {
            return Optional.empty();
        }
        try {
            return Optional.of(new BigDecimal(fraction.isEmpty() ? integerPart : integerPart + "." + fraction));
        } catch (NumberFormatException e) {
            return Optional.empty();
        }
    }
}
