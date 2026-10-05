package com.idp.extraction.typology;

/** Definicion de un campo (escalar o columna de tabla). {@code validator} puede ser null. */
public record FieldDef(String name, FieldType type, String description, boolean critico, String validator,
                       double umbralAuto, double umbralRevisar) {
}
