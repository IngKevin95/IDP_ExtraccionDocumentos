package com.idp.extraction.core;

import com.idp.extraction.reliability.Grounding;
import com.idp.extraction.reliability.ReliabilityEngine;
import com.idp.extraction.reliability.ReliabilityEngine.Decision;
import com.idp.extraction.reliability.ReliabilityEngine.FieldScore;
import com.idp.extraction.typology.FieldDef;
import com.idp.extraction.typology.FieldType;
import com.idp.extraction.typology.TableDef;
import com.idp.extraction.typology.TypologyDef;
import com.idp.extraction.validation.AmountParser;
import com.idp.extraction.validation.TableSumValidator;
import com.idp.extraction.validation.ValidationContext;
import com.idp.extraction.validation.ValidationResult;
import com.idp.extraction.validation.ValidatorRegistry;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** Evalua cada campo (validadores, grounding, evidencia, score calibrado) y decide su tramo de ruteo. */
public final class ExtractionEvaluator {

    /** Tabla y columnas sobre las que aplica la regla RN-05 (suma de tabla contra total general). */
    public static final String SUM_TABLE = "demandados";
    public static final String SUM_COLUMN = "monto";
    public static final String SUM_TOTAL_FIELD = "monto_numeros";

    /** Evaluacion de un campo; {@code skipped} indica campo opcional sin valor (no se rutea). */
    public record FieldEval(FieldKey key, FieldDef def, ExtractedValue value, FieldScore score, Decision decision,
                            List<ValidationResult> validations, boolean skipped, String errorCode) {

        public boolean requiresReview() {
            return !skipped && decision != Decision.AUTO;
        }
    }

    private final ValidatorRegistry validators;
    private final ReliabilityEngine reliability;
    private final Grounding grounding;
    private final TableSumValidator tableSum = new TableSumValidator();

    public ExtractionEvaluator(ValidatorRegistry validators, ReliabilityEngine reliability, Grounding grounding) {
        this.validators = validators;
        this.reliability = reliability;
        this.grounding = grounding;
    }

    public Map<FieldKey, FieldEval> evaluate(TypologyDef typology, ExtractionData data, String nativeText,
                                             LocalDate today) {
        Map<FieldKey, FieldEval> out = new LinkedHashMap<>();
        Map<String, String> doc = data.scalarValues();
        ValidationContext docCtx = ValidationContext.of(doc, today);
        for (FieldDef f : typology.fields()) {
            FieldKey key = FieldKey.scalar(f.name());
            out.put(key, evaluateOne(key, f, data.get(key), docCtx, nativeText));
        }
        for (TableDef t : typology.tables()) {
            List<Map<String, String>> rows = data.rowValues(t.name());
            for (int r = 0; r < rows.size(); r++) {
                ValidationContext ctx = new ValidationContext(doc, rows.get(r), today);
                for (FieldDef col : t.fields()) {
                    FieldKey key = FieldKey.cell(t.name(), r, col.name());
                    out.put(key, evaluateOne(key, col, data.get(key), ctx, nativeText));
                }
            }
        }
        applyTableSum(typology, data, doc, out);
        return out;
    }

    private FieldEval evaluateOne(FieldKey key, FieldDef def, ExtractedValue value, ValidationContext ctx,
                                  String nativeText) {
        if (value == null || !value.present()) {
            if (def.critico()) {
                FieldScore s = reliability.score(def, null, List.of(), Grounding.Status.NOT_REQUIRED);
                return new FieldEval(key, def, value, s, Decision.REVIEW, List.of(), false, "CAMPO_CRITICO_AUSENTE");
            }
            return new FieldEval(key, def, value, null, Decision.AUTO, List.of(), true, null);
        }
        List<ValidationResult> results = new ArrayList<>();
        if (def.validator() != null) {
            results.add(validators.find(def.validator()).orElseThrow().validate(value.value(), ctx));
        }
        ValidationResult typeCheck = typeCheck(def, value.value());
        if (typeCheck != null) {
            results.add(typeCheck);
        }
        Grounding.Status g = grounding.check(def, value, nativeText);
        FieldScore score = reliability.score(def, value, results, g);
        String error = results.stream().filter(ValidationResult::failed).map(ValidationResult::code)
            .collect(Collectors.joining(","));
        return new FieldEval(key, def, value, score, reliability.route(def, score), results, false,
            error.isEmpty() ? null : error);
    }

    private static ValidationResult typeCheck(FieldDef def, String value) {
        if (def.type() == FieldType.DECIMAL && AmountParser.parse(value).isEmpty()) {
            return ValidationResult.fail("TIPO_DECIMAL_INVALIDO", "Valor no decimal");
        }
        if (def.type() == FieldType.DATE) {
            try {
                LocalDate.parse(value);
            } catch (DateTimeParseException e) {
                return ValidationResult.fail("TIPO_FECHA_INVALIDA", "Fecha no ISO-8601");
            }
        }
        return null;
    }

    /** RN-05: si la suma de la tabla no coincide con el total, total y celdas de monto van a revision. */
    private void applyTableSum(TypologyDef typology, ExtractionData data, Map<String, String> doc,
                               Map<FieldKey, FieldEval> evals) {
        if (typology.table(SUM_TABLE).isEmpty() || typology.field(SUM_TOTAL_FIELD).isEmpty()) {
            return;
        }
        ValidationResult r = tableSum.validate(data.rowValues(SUM_TABLE), SUM_COLUMN, doc.get(SUM_TOTAL_FIELD));
        if (!r.failed()) {
            return;
        }
        List<FieldKey> targets = new ArrayList<>();
        targets.add(FieldKey.scalar(SUM_TOTAL_FIELD));
        evals.keySet().stream().filter(k -> k.isCell() && k.table().equals(SUM_TABLE) && k.name().equals(SUM_COLUMN))
            .forEach(targets::add);
        for (FieldKey k : targets) {
            FieldEval e = evals.get(k);
            if (e == null || e.skipped()) {
                continue;
            }
            List<ValidationResult> v = new ArrayList<>(e.validations());
            v.add(r);
            FieldScore s = reliability.score(e.def(), e.value(), v, e.score() == null
                ? Grounding.Status.NOT_REQUIRED : e.score().grounding());
            String code = e.errorCode() == null ? r.code() : e.errorCode() + "," + r.code();
            evals.put(k, new FieldEval(k, e.def(), e.value(), s, Decision.REVIEW, v, false, code));
        }
    }
}
