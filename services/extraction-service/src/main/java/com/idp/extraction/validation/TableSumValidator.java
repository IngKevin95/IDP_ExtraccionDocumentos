package com.idp.extraction.validation;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** RN-05: la suma de la columna de valores de una tabla debe coincidir con el total general. */
public final class TableSumValidator {

    public ValidationResult validate(List<Map<String, String>> rows, String column, String total) {
        if (total == null || total.isBlank() || rows == null || rows.isEmpty()) {
            return ValidationResult.notApplicable();
        }
        Optional<BigDecimal> expected = AmountParser.parse(total);
        if (expected.isEmpty()) {
            return ValidationResult.fail("SUMA_TOTAL_ILEGIBLE", "Total general no interpretable");
        }
        BigDecimal sum = BigDecimal.ZERO;
        int counted = 0;
        for (Map<String, String> row : rows) {
            String cell = row.get(column);
            if (cell == null || cell.isBlank()) {
                continue;
            }
            Optional<BigDecimal> amount = AmountParser.parse(cell);
            if (amount.isEmpty()) {
                return ValidationResult.fail("SUMA_CELDA_ILEGIBLE", "Valor de fila no interpretable");
            }
            sum = sum.add(amount.get());
            counted++;
        }
        if (counted == 0) {
            return ValidationResult.notApplicable();
        }
        return sum.compareTo(expected.get()) == 0
            ? ValidationResult.pass()
            : ValidationResult.fail("SUMA_DISCREPANCIA", "La suma de la tabla no coincide con el total");
    }
}
