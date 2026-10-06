package com.idp.chat.service;

import java.text.Normalizer;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extrae de un texto los hechos verificables (SEC-048): cifras, montos (en digitos o en letras), fechas y monedas
 * extranjeras, normalizados a una forma canonica para compararlos entre la respuesta y el documento aunque el
 * formato cambie ("$1.500.000,00" = "1500000" = "un millon quinientos mil"; "15 de marzo de 2024" = "15/03/2024").
 * Tokens: {@code N:<numero>}, {@code D:<yyyy-MM-dd>} o {@code D:--MM-dd} (sin anio), {@code C:USD|EUR}.
 */
final class FactTokens {

    private static final String MONTHS = "enero|febrero|marzo|abril|mayo|junio|julio|agosto|septiembre|setiembre|"
            + "octubre|noviembre|diciembre";
    private static final List<String> MONTH_LIST = List.of("enero", "febrero", "marzo", "abril", "mayo", "junio",
            "julio", "agosto", "septiembre", "octubre", "noviembre", "diciembre");

    private static final Pattern ISO = Pattern.compile("\\b(\\d{4})[/\\-](\\d{1,2})[/\\-](\\d{1,2})\\b");
    private static final Pattern DMY_NUM = Pattern.compile("\\b(\\d{1,2})[/\\-.](\\d{1,2})[/\\-.](\\d{4})\\b");
    private static final Pattern DMY_TEXT = Pattern.compile("\\b(\\d{1,2})\\s+de\\s+(" + MONTHS
            + ")(?:\\s+de(?:l)?\\s+(\\d{4}))?\\b");
    private static final Pattern MDY_TEXT = Pattern.compile("\\b(" + MONTHS + ")\\s+(\\d{1,2})(?:\\s*,\\s*|\\s+de\\s+)"
            + "(\\d{4})\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+(?:[.,]\\d+)*");
    private static final Pattern CURRENCY = Pattern.compile("\\b(usd|dolar(?:es)?|eur|euros?)\\b");
    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern WORD = Pattern.compile("[a-z]+");

    private static final int MIL = -1;
    private static final int MILLON = -2;
    private static final Map<String, Integer> WORDS = words();

    private FactTokens() {
    }

    static Set<String> extract(String text) {
        Set<String> out = new LinkedHashSet<>();
        if (text == null || text.isEmpty()) {
            return out;
        }
        String s = DIACRITICS.matcher(Normalizer.normalize(Normalizer.normalize(text, Normalizer.Form.NFKC)
                .toLowerCase(Locale.ROOT), Normalizer.Form.NFD)).replaceAll("");
        s = dates(s, ISO, out, 0);
        s = dates(s, DMY_TEXT, out, 1);
        s = dates(s, MDY_TEXT, out, 2);
        s = dates(s, DMY_NUM, out, 3);
        s = numberWords(s, out);
        Matcher c = CURRENCY.matcher(s);
        while (c.find()) {
            out.add(c.group(1).startsWith("eur") ? "C:EUR" : "C:USD");
        }
        Matcher m = NUMBER.matcher(s);
        while (m.find()) {
            out.add("N:" + canonicalNumber(m.group()));
        }
        return out;
    }

    // ---- fechas ------------------------------------------------------------------------------------------------

    /** Anota las fechas que casan con el patron (formato {@code kind}) y las borra del texto. */
    private static String dates(String s, Pattern p, Set<String> out, int kind) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String token = switch (kind) {
                case 0 -> full(m.group(1), Integer.parseInt(m.group(2)), m.group(3));
                case 1 -> m.group(3) == null ? partial(m.group(2), m.group(1))
                        : full(m.group(3), month(m.group(2)), m.group(1));
                case 2 -> full(m.group(3), month(m.group(1)), m.group(2));
                default -> full(m.group(3), Integer.parseInt(m.group(2)), m.group(1));
            };
            if (token == null) {
                m.appendReplacement(sb, Matcher.quoteReplacement(m.group()));
            } else {
                out.add("D:" + token);
                m.appendReplacement(sb, " ");
            }
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static int month(String name) {
        return MONTH_LIST.indexOf("setiembre".equals(name) ? "septiembre" : name) + 1;
    }

    private static String full(String year, int month, String day) {
        try {
            return LocalDate.of(Integer.parseInt(year), month, Integer.parseInt(day)).toString();
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static String partial(String monthName, String day) {
        int d = Integer.parseInt(day);
        if (d < 1 || d > 31) {
            return null;
        }
        return String.format("--%02d-%02d", month(monthName), d);
    }

    // ---- numeros -----------------------------------------------------------------------------------------------

    /** Forma canonica es-CO tolerante: "." miles y "," decimales; un unico separador con 3 digitos es de miles. */
    static String canonicalNumber(String raw) {
        String s = raw;
        int dot = s.lastIndexOf('.');
        int comma = s.lastIndexOf(',');
        if (dot >= 0 && comma >= 0) {
            char dec = dot > comma ? '.' : ',';
            char thou = dec == '.' ? ',' : '.';
            s = s.replace(String.valueOf(thou), "").replace(dec, '.');
        } else if (dot >= 0 || comma >= 0) {
            char sep = dot >= 0 ? '.' : ',';
            int count = (int) s.chars().filter(ch -> ch == sep).count();
            int idx = s.indexOf(sep);
            String before = s.substring(0, idx);
            int after = s.length() - idx - 1;
            boolean thousands = count > 1 || (after == 3 && before.length() <= 3 && !before.startsWith("0"));
            s = thousands ? s.replace(String.valueOf(sep), "") : s.replace(sep, '.');
        }
        String intPart = s;
        String frac = "";
        int p = s.indexOf('.');
        if (p >= 0) {
            intPart = s.substring(0, p);
            frac = s.substring(p + 1).replaceAll("0+$", "");
        }
        intPart = intPart.replaceFirst("^0+(?=\\d)", "");
        return frac.isEmpty() ? intPart : intPart + "." + frac;
    }

    /** Sustituye las cifras escritas en letras por su valor ({@code N:valor}) y las borra del texto. */
    private static String numberWords(String s, Set<String> out) {
        Matcher m = WORD.matcher(s);
        StringBuilder sb = new StringBuilder();
        List<String> run = new ArrayList<>();
        int last = 0;
        int runStart = -1;
        int runEnd = -1;
        while (m.find()) {
            String w = m.group();
            boolean number = WORDS.containsKey(w);
            boolean connector = "y".equals(w) && !run.isEmpty();
            boolean adjacent = !run.isEmpty() && s.substring(runEnd, m.start()).isBlank();
            if ((number || connector) && adjacent) {
                run.add(w);
                runEnd = m.end();
                continue;
            }
            last = flush(s, sb, last, runStart, runEnd, run, out);
            if (number) {
                run.add(w);
                runStart = m.start();
                runEnd = m.end();
            }
        }
        last = flush(s, sb, last, runStart, runEnd, run, out);
        return sb.append(s, last, s.length()).toString();
    }

    private static int flush(String s, StringBuilder sb, int last, int runStart, int runEnd, List<String> run,
                             Set<String> out) {
        if (run.isEmpty()) {
            return last;
        }
        while (!run.isEmpty() && "y".equals(run.get(run.size() - 1))) {
            run.remove(run.size() - 1);
        }
        long value = value(run);
        boolean article = run.size() == 1 && value == 1;
        int next = last;
        if (!run.isEmpty() && !article) {
            out.add("N:" + value);
            sb.append(s, last, runStart).append(' ');
            next = runEnd;
        }
        run.clear();
        return next;
    }

    private static long value(List<String> run) {
        long result = 0;
        long cur = 0;
        for (String w : run) {
            Integer v = WORDS.get(w);
            if (v == null) {
                continue;
            }
            if (v == MIL) {
                result += (cur == 0 ? 1 : cur) * 1000;
                cur = 0;
            } else if (v == MILLON) {
                if (cur == 0 && result > 0) {
                    result *= 1_000_000;
                } else {
                    result += (cur == 0 ? 1 : cur) * 1_000_000L;
                }
                cur = 0;
            } else {
                cur += v;
            }
        }
        return result + cur;
    }

    private static Map<String, Integer> words() {
        Map<String, Integer> m = new HashMap<>();
        String[] units = {"cero", "un", "dos", "tres", "cuatro", "cinco", "seis", "siete", "ocho", "nueve", "diez",
            "once", "doce", "trece", "catorce", "quince", "dieciseis", "diecisiete", "dieciocho", "diecinueve",
            "veinte"};
        for (int i = 0; i < units.length; i++) {
            m.put(units[i], i);
        }
        m.put("uno", 1);
        m.put("una", 1);
        String[] twenties = {"veintiun", "veintidos", "veintitres", "veinticuatro", "veinticinco", "veintiseis",
            "veintisiete", "veintiocho", "veintinueve"};
        for (int i = 0; i < twenties.length; i++) {
            m.put(twenties[i], 21 + i);
        }
        m.put("veintiuno", 21);
        m.put("veintiuna", 21);
        String[] tens = {"treinta", "cuarenta", "cincuenta", "sesenta", "setenta", "ochenta", "noventa"};
        for (int i = 0; i < tens.length; i++) {
            m.put(tens[i], 30 + 10 * i);
        }
        m.put("cien", 100);
        m.put("ciento", 100);
        String[] hundreds = {"doscientos", "trescientos", "cuatrocientos", "quinientos", "seiscientos", "setecientos",
            "ochocientos", "novecientos"};
        for (int i = 0; i < hundreds.length; i++) {
            m.put(hundreds[i], 200 + 100 * i);
            m.put(hundreds[i].substring(0, hundreds[i].length() - 2) + "as", 200 + 100 * i);
        }
        m.put("mil", MIL);
        m.put("millon", MILLON);
        m.put("millones", MILLON);
        return Map.copyOf(m);
    }
}
