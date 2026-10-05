package com.idp.extraction.typology;

import java.util.List;
import java.util.Optional;

public record TypologyDef(String id, String code, String name, int version, String description,
                          String promptFewShot, List<FieldDef> fields, List<TableDef> tables) {
    public TypologyDef {
        fields = List.copyOf(fields);
        tables = List.copyOf(tables);
    }

    public Optional<FieldDef> field(String name) {
        return fields.stream().filter(f -> f.name().equals(name)).findFirst();
    }

    public Optional<TableDef> table(String name) {
        return tables.stream().filter(t -> t.name().equals(name)).findFirst();
    }
}
