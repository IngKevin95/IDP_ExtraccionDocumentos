package com.idp.extraction.core;

/** Direccion de un campo: escalar ({@code table == null}) o celda de tabla (tabla, fila, columna). */
public record FieldKey(String table, int row, String name) {

    public static FieldKey scalar(String name) {
        return new FieldKey(null, -1, name);
    }

    public static FieldKey cell(String table, int row, String name) {
        return new FieldKey(table, row, name);
    }

    public boolean isCell() {
        return table != null;
    }

    @Override
    public String toString() {
        return table == null ? name : table + "[" + row + "]." + name;
    }
}
