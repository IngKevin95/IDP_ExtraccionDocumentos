package com.idp.extraction.typology;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.extraction.validation.ValidatorRegistry;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/**
 * Carga y valida las tipologias YAML contra tipologia.schema.json al arrancar. Falla rapido
 * ({@link InvalidTypologyException}) si el YAML es invalido o referencia un validador inexistente.
 * Las versiones cargadas son inmutables; solo la bandera de activacion cambia.
 */
public final class TypologyRegistry {

    public static final String DEFAULT_YAML = "contracts/tipologias/embargos.v1.yaml";
    public static final String DEFAULT_SCHEMA = "contracts/tipologias/tipologia.schema.json";

    private final List<TypologyDef> definitions;
    private final Set<String> inactiveCodes = ConcurrentHashMap.newKeySet();

    public TypologyRegistry(List<TypologyDef> definitions, ValidatorRegistry validators) {
        Set<String> seen = new HashSet<>();
        for (TypologyDef def : definitions) {
            if (!seen.add(def.code() + "@" + def.version())) {
                throw new InvalidTypologyException("Tipologia duplicada: " + def.code() + " v" + def.version());
            }
            verifyValidators(def, validators);
        }
        this.definitions = List.copyOf(definitions);
    }

    /** Carga las tipologias empaquetadas como recurso del build. */
    public static TypologyRegistry loadDefault(ValidatorRegistry validators) {
        ClassLoader cl = TypologyRegistry.class.getClassLoader();
        try (InputStream yaml = cl.getResourceAsStream(DEFAULT_YAML);
             InputStream schema = cl.getResourceAsStream(DEFAULT_SCHEMA)) {
            if (yaml == null || schema == null) {
                throw new InvalidTypologyException("Tipologias no empaquetadas en el classpath");
            }
            return load(yaml, schema, validators);
        } catch (IOException e) {
            throw new InvalidTypologyException("No se pudo leer la tipologia", e);
        }
    }

    public static TypologyRegistry load(InputStream yaml, InputStream schema, ValidatorRegistry validators) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode tree;
        try {
            Object parsed = new Yaml(new SafeConstructor(new LoaderOptions())).load(yaml);
            tree = mapper.valueToTree(parsed);
        } catch (RuntimeException e) {
            throw new InvalidTypologyException("YAML de tipologia mal formado", e);
        }
        if (tree == null || tree.isNull()) {
            throw new InvalidTypologyException("YAML de tipologia vacio");
        }
        JsonSchema jsonSchema = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(schema);
        Set<ValidationMessage> errors = jsonSchema.validate(tree);
        if (!errors.isEmpty()) {
            throw new InvalidTypologyException("Tipologia no cumple el esquema: "
                + errors.stream().map(ValidationMessage::getMessage).sorted().collect(Collectors.joining("; ")));
        }
        List<TypologyDef> defs = new ArrayList<>();
        for (JsonNode n : tree) {
            defs.add(toDef(n));
        }
        return new TypologyRegistry(defs, validators);
    }

    private static TypologyDef toDef(JsonNode n) {
        List<FieldDef> fields = new ArrayList<>();
        n.path("fields").forEach(f -> fields.add(toField(f)));
        List<TableDef> tables = new ArrayList<>();
        n.path("tables").forEach(t -> {
            List<FieldDef> cols = new ArrayList<>();
            t.path("fields").forEach(f -> cols.add(toField(f)));
            tables.add(new TableDef(t.get("name").asText(), t.get("description").asText(), cols));
        });
        return new TypologyDef(n.get("id").asText(), n.get("code").asText(), n.get("name").asText(),
            n.get("version").asInt(), n.get("description").asText(), n.path("prompt_few_shot").asText(""),
            fields, tables);
    }

    private static FieldDef toField(JsonNode f) {
        double auto = f.get("umbral_auto").asDouble();
        double revisar = f.get("umbral_revisar").asDouble();
        if (revisar > auto) {
            throw new InvalidTypologyException("umbral_revisar mayor que umbral_auto en " + f.get("name").asText());
        }
        return new FieldDef(f.get("name").asText(), FieldType.parse(f.get("type").asText()),
            f.get("description").asText(), f.get("critico").asBoolean(),
            f.hasNonNull("validator") ? f.get("validator").asText() : null, auto, revisar);
    }

    private static void verifyValidators(TypologyDef def, ValidatorRegistry validators) {
        List<FieldDef> all = new ArrayList<>(def.fields());
        def.tables().forEach(t -> all.addAll(t.fields()));
        for (FieldDef f : all) {
            if (f.validator() != null && !validators.contains(f.validator())) {
                throw new InvalidTypologyException("Validador no encontrado: " + f.validator()
                    + " (campo " + f.name() + ", tipologia " + def.code() + ")");
            }
        }
    }

    /** Version activa mas alta del codigo, si la tipologia esta activa. */
    public Optional<TypologyDef> active(String code) {
        if (inactiveCodes.contains(code)) {
            return Optional.empty();
        }
        return definitions.stream().filter(d -> d.code().equals(code))
            .max(Comparator.comparingInt(TypologyDef::version));
    }

    public Optional<TypologyDef> find(String code, int version) {
        return definitions.stream().filter(d -> d.code().equals(code) && d.version() == version).findFirst();
    }

    public void setActive(String code, boolean active) {
        if (active) {
            inactiveCodes.remove(code);
        } else {
            inactiveCodes.add(code);
        }
    }

    /** Codigos con al menos una version activa. */
    public List<TypologyDef> activeDefinitions() {
        return definitions.stream().map(TypologyDef::code).distinct().sorted()
            .map(this::active).flatMap(Optional::stream).toList();
    }
}
