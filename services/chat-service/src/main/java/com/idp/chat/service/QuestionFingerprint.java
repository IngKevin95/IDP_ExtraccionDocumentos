package com.idp.chat.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Huellas de una pregunta para la cache semantica y la cache de embeddings (SEC-048): {@code hash} de la pregunta
 * normalizada (minusculas, sin acentos ni puntuacion, espacios colapsados) y {@code signature} del conjunto de tokens
 * significativos: cifras, montos y fechas canonicos, identificadores alfanumericos y nombres propios. Dos preguntas que
 * solo difieren en una cifra o un nombre tienen firma distinta aunque su coseno sea casi 1.
 */
public record QuestionFingerprint(String hash, String signature) {

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");
    private static final Pattern WITH_DIGIT = Pattern.compile("[\\p{L}\\p{N}]*\\p{N}[\\p{L}\\p{N}]*");
    private static final Pattern RAW_TOKEN = Pattern.compile("[\\p{L}\\p{N}][\\p{L}\\p{N}'-]*");

    public static QuestionFingerprint of(String question) {
        String nfkc = Normalizer.normalize(question, Normalizer.Form.NFKC);
        return new QuestionFingerprint(sha256(normalize(nfkc)), sha256(String.join("|", significant(nfkc))));
    }

    static String normalize(String text) {
        String s = DIACRITICS.matcher(Normalizer.normalize(text.toLowerCase(Locale.ROOT), Normalizer.Form.NFD))
                .replaceAll("");
        return NON_ALNUM.matcher(s).replaceAll(" ").strip();
    }

    private static Set<String> significant(String nfkc) {
        Set<String> tokens = new TreeSet<>(FactTokens.extract(nfkc));
        String lower = normalize(nfkc);
        Matcher d = WITH_DIGIT.matcher(lower);
        while (d.find()) {
            tokens.add("T:" + d.group());
        }
        Matcher m = RAW_TOKEN.matcher(nfkc);
        boolean sentenceStart = true;
        int last = 0;
        while (m.find()) {
            String between = nfkc.substring(last, m.start());
            if (between.matches("(?s).*[.?!:;¿¡].*")) {
                sentenceStart = true;
            }
            String w = m.group();
            if (!sentenceStart && Character.isUpperCase(w.codePointAt(0)) && w.length() > 1) {
                tokens.add("P:" + normalize(w));
            }
            sentenceStart = false;
            last = m.end();
        }
        return tokens;
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
