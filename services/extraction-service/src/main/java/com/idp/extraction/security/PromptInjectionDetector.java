package com.idp.extraction.security;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Filtro heuristico preventivo de prompt injection sobre la capa de texto del oficio (SEC-033).
 * Normaliza (minusculas, sin acentos, sin caracteres invisibles) y aplica patrones acotados
 * (sin cuantificadores anidados) sobre un maximo de caracteres. Devuelve solo el id de la regla:
 * nunca el contenido del documento.
 */
public final class PromptInjectionDetector {

    static final int MAX_SCAN_CHARS = 2_000_000;

    private record Rule(String id, Pattern pattern) {
    }

    private static final List<Rule> RULES = List.of(
        rule("IGNORAR_INSTRUCCIONES",
            "\\b(ignora\\w*|ignore|olvida\\w*|desestima\\w*|omite|omitir|descarta\\w*|disregard|forget|override)"
                + "\\W+(\\w+\\W+){0,3}(reglas|instrucciones|indicaciones|directrices|restricciones|"
                + "instructions|rules|prompts?|guidelines|restrictions)"),
        rule("PROMPT_DE_SISTEMA", "\\b(system|sistema)\\W+(prompt|message|mensaje)"),
        rule("CAMBIO_DE_ROL",
            "\\b(eres ahora|ahora eres|you are now|actua como|act as|finge ser|pretend to be|fingir ser)\\b"),
        rule("EXFILTRACION",
            "\\b(revela\\w*|muestra\\w*|imprime|imprimir|reveal|print|show|leak|divulga\\w*)\\W+(\\w+\\W+){0,3}"
                + "(prompt|instrucciones|instructions|contrasenas?|passwords?|secretos?|secrets?|claves?|api\\W?keys?)"),
        rule("FORZAR_SCORE",
            "\\b(marca|mark|set|establece|asigna|pon)\\W+(\\w+\\W+){0,4}(confianza|confidence|score)\\W+"
                + "(\\w+\\W+){0,3}(1|100|alta|high|maxim\\w*)\\b"),
        rule("DELIMITADOR_FALSIFICADO",
            "(<\\W{0,3}(system|assistant|instructions?)\\W{0,3}>|\\[\\W?/?(inst|system)\\W?\\]|<<<\\W{0,3}(doc|fin|end))"),
        rule("JAILBREAK", "\\b(jailbreak|dan mode|modo desarrollador|developer mode)\\b"));

    private static Rule rule(String id, String regex) {
        return new Rule(id, Pattern.compile(regex));
    }

    /** Id de la primera regla que coincide, o vacio si el texto parece limpio. */
    public Optional<String> scan(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        String bounded = text.length() > MAX_SCAN_CHARS ? text.substring(0, MAX_SCAN_CHARS) : text;
        String normalized = normalize(bounded);
        for (Rule r : RULES) {
            if (r.pattern().matcher(normalized).find()) {
                return Optional.of(r.id());
            }
        }
        return Optional.empty();
    }

    static String normalize(String s) {
        return Normalizer.normalize(s.toLowerCase(Locale.ROOT), Normalizer.Form.NFD)
            .replaceAll("[\\p{M}\\p{Cf}]", "")
            .replaceAll("\\s+", " ");
    }
}
