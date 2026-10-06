package com.idp.chat.service;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Clasificador heuristico es/en de prompt injection (SEC-033), previo al LLM. Normaliza (NFKC, minusculas, sin
 * acentos, sin puntuacion) para resistir variantes tipograficas y evalua reglas acotadas. Se aplica a la pregunta del
 * usuario (directa) y a los fragmentos recuperados (indirecta). Devuelve el id de regla, nunca el texto.
 */
@Component
public class PromptInjectionDetector {

    private record Rule(String id, Pattern pattern) {
    }

    private static final String VERB_IGNORE = "(?:ignora\\w*|ignore|olvida\\w*|forget|disregard|omite|omit|descarta\\w*|"
            + "desestima\\w*|salta\\w*|skip|override|anula\\w*|sobrescribe|reemplaza\\w*|replace)";
    private static final String OBJ_RULES = "(?:instrucciones|instruccion|reglas|regla|indicaciones|directrices|"
            + "directivas|restricciones|politicas|prompt|prompts|instructions|instruction|rules|guidelines|directives|"
            + "restrictions|policies|programacion|programming|guardrails)";

    private static final List<Rule> RULES = List.of(
            new Rule("ignore_instructions",
                    Pattern.compile("\\b" + VERB_IGNORE + "\\s+(?:\\w+\\s+){0,4}?" + OBJ_RULES + "\\b")),
            new Rule("ignore_previous",
                    Pattern.compile("\\b" + VERB_IGNORE + "\\s+(?:todo\\s+|all\\s+|everything\\s+)?(?:lo\\s+)?"
                            + "(?:anterior|previo|previous|prior|above|before|arriba)\\b")),
            new Rule("reveal_prompt",
                    Pattern.compile("\\b(?:muestra\\w*|revela\\w*|imprime\\w*|repite\\w*|dime|dame|show|reveal|print|"
                            + "repeat|dump|leak|display|tell me)\\s+(?:\\w+\\s+){0,3}?(?:tu|tus|el|los|las|your|the|"
                            + "its|hidden|secret|oculto\\w*)\\s+(?:\\w+\\s+){0,2}?(?:prompt|instrucciones|"
                            + "instructions|reglas|rules|system prompt)\\b")),
            new Rule("system_prompt", Pattern.compile("\\b(?:system|developer|sistema)\\s+prompt\\b|"
                    + "\\bprompt\\s+(?:del\\s+)?(?:sistema|system|developer)\\b")),
            new Rule("role_override",
                    Pattern.compile("\\b(?:you\\s+are\\s+now|ahora\\s+eres|a\\s+partir\\s+de\\s+ahora\\s+(?:eres|"
                            + "actuas|responde\\w*)|from\\s+now\\s+on\\s+you|pretend\\s+(?:to\\s+be|you\\s+are)|"
                            + "finge\\s+(?:ser|que\\s+eres)|actua\\s+como\\s+(?:si\\s+no|un\\s+(?:hacker|atacante))|"
                            + "act\\s+as\\s+(?:if\\s+you\\s+have\\s+no|an?\\s+(?:unrestricted|evil|hacker)))\\b")),
            new Rule("jailbreak", Pattern.compile("\\b(?:jailbreak|dan\\s+mode|do\\s+anything\\s+now|modo\\s+dios|"
                    + "god\\s+mode|developer\\s+mode|modo\\s+desarrollador|sin\\s+(?:ninguna\\s+)?(?:restricciones|"
                    + "filtros|censura|limites)|without\\s+(?:any\\s+)?(?:restrictions|filters|limits|censorship))\\b")),
            new Rule("bypass_controls",
                    Pattern.compile("\\b(?:bypass|evade|elude|sortea\\w*|burla\\w*|saltate|disable|desactiva\\w*|"
                            + "deshabilita\\w*)\\s+(?:\\w+\\s+){0,2}?(?:filtros?|filters?|restricciones|restrictions|"
                            + "seguridad|security|guardrails|safety|controles|politicas|moderacion|moderation)\\b")));

    /** Marcadores de plantilla de chat o de rol inyectados tal cual (antes de normalizar). */
    private static final Pattern RAW_MARKERS = Pattern.compile("(?i)<\\|?\\s*(?:im_start|im_end|system|endoftext)\\s*\\|?>"
            + "|\\[/?inst\\]|<<\\s*sys\\s*>>|(?m)^\\s*#{2,}\\s*(?:system|instruction)\\b");

    private static final Pattern DIACRITICS = Pattern.compile("\\p{M}+");
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]+");

    /** @return el id de la regla que dispara, o vacio si el texto parece legitimo */
    public Optional<String> detect(String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        if (RAW_MARKERS.matcher(text).find()) {
            return Optional.of("chat_template_marker");
        }
        String n = normalize(text);
        for (Rule r : RULES) {
            if (r.pattern().matcher(n).find()) {
                return Optional.of(r.id());
            }
        }
        return Optional.empty();
    }

    static String normalize(String text) {
        String s = Normalizer.normalize(text, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        s = DIACRITICS.matcher(Normalizer.normalize(s, Normalizer.Form.NFD)).replaceAll("");
        return NON_ALNUM.matcher(s).replaceAll(" ").strip();
    }
}
