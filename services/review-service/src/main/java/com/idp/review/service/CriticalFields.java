package com.idp.review.service;

import com.idp.review.config.ReviewProperties;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Component;

/**
 * Campos criticos (RF-302, SEC-009): monto, identificacion, cuenta, producto, tipo de medida (y radicado). Se compara
 * por fragmento sobre el nombre normalizado, de modo que monto_numeros, numero_identificacion, productos_o_cuentas o
 * demandados[0].monto cuentan como criticos. El sesgo es conservador: ante la duda se exigen cuatro ojos.
 */
@Component
public class CriticalFields {

    private final List<String> keywords;

    public CriticalFields(ReviewProperties props) {
        this.keywords = props.criticalFields().stream().map(CriticalFields::normalize).filter(k -> !k.isEmpty())
                .toList();
    }

    public boolean isCritical(String fieldName) {
        String n = normalize(fieldName);
        return keywords.stream().anyMatch(n::contains);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT);
    }
}
