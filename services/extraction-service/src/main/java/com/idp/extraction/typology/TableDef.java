package com.idp.extraction.typology;

import java.util.List;

public record TableDef(String name, String description, List<FieldDef> fields) {
    public TableDef {
        fields = List.copyOf(fields);
    }
}
