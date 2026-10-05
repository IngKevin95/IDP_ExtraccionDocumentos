package com.idp.extraction.typology;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.extraction.validation.ValidatorRegistry;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/** Especificacion tipologias AC-01 a AC-08 y validadores AC-09 (dependencia estricta de validadores). */
class TypologyRegistryTest {

    private static final ValidatorRegistry VALIDATORS = ValidatorRegistry.defaults();

    private static InputStream schema() {
        return TypologyRegistry.class.getClassLoader().getResourceAsStream(TypologyRegistry.DEFAULT_SCHEMA);
    }

    private static TypologyRegistry load(String yaml) {
        return TypologyRegistry.load(new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8)), schema(), VALIDATORS);
    }

    private static String typology(String code, int version, String fieldExtras, String validator) {
        return """
            - id: t.%s.%d
              code: %s
              name: "Tipologia de prueba"
              version: %d
              description: "desc"
              fields:
                - name: radicado
                  type: string
                  description: "Radicado"
                  critico: true
                  %s
                  umbral_auto: 0.95
                  umbral_revisar: 0.70
                  %s
            """.formatted(code.toLowerCase(), version, code, version, validator, fieldExtras);
    }

    @Test
    void ac01_cargaExitosaDelYamlEmpaquetado() {
        TypologyRegistry registry = TypologyRegistry.loadDefault(VALIDATORS);
        assertThat(registry.activeDefinitions()).extracting(TypologyDef::code).containsExactly("DC", "DJ", "EC", "EJ");
        assertThat(registry.active("EC")).isPresent();
    }

    @Test
    void ac02_yamlMalFormadoFallaElArranque() {
        assertThatThrownBy(() -> load("- id: [sin cerrar")).isInstanceOf(InvalidTypologyException.class);
        assertThatThrownBy(() -> load("")).isInstanceOf(InvalidTypologyException.class);
    }

    @Test
    void ac02_camposObligatoriosFaltantesFallanContraElEsquema() {
        String sinCodigo = """
            - id: x
              name: "n"
              version: 1
              description: "d"
              fields: []
            """;
        assertThatThrownBy(() -> load(sinCodigo)).isInstanceOf(InvalidTypologyException.class)
            .hasMessageContaining("esquema");
        String campoSinUmbral = """
            - id: x
              code: EC
              name: "n"
              version: 1
              description: "d"
              fields:
                - name: a
                  type: string
                  description: "d"
                  critico: false
            """;
        assertThatThrownBy(() -> load(campoSinUmbral)).isInstanceOf(InvalidTypologyException.class);
    }

    @Test
    void ac02_umbralRevisarNoPuedeSuperarUmbralAuto() {
        String yaml = typology("EC", 1, "", "").replace("umbral_revisar: 0.70", "umbral_revisar: 0.99");
        assertThatThrownBy(() -> load(yaml)).isInstanceOf(InvalidTypologyException.class)
            .hasMessageContaining("umbral_revisar");
    }

    @Test
    void ac03_campoCriticoSeExponeEnElEsquema() {
        TypologyDef ec = TypologyRegistry.loadDefault(VALIDATORS).active("EC").orElseThrow();
        assertThat(ec.field("radicado").orElseThrow().critico()).isTrue();
        assertThat(ec.field("ciudad").orElseThrow().critico()).isFalse();
    }

    @Test
    void ac04_validadorInexistenteFallaElArranque() {
        String yaml = typology("EC", 1, "", "validator: validador_ficticio");
        assertThatThrownBy(() -> load(yaml)).isInstanceOf(InvalidTypologyException.class)
            .hasMessageContaining("Validador no encontrado: validador_ficticio");
    }

    @Test
    void ac04_validadorExistenteEsAceptado() {
        assertThat(load(typology("EC", 1, "", "validator: radicado_23_digitos")).active("EC")).isPresent();
    }

    @Test
    void ac05_versionadoUsaLaVersionActivaMasAlta() {
        String yaml = typology("EC", 1, "", "validator: radicado_23_digitos") + "\n"
            + typology("EC", 2, "", "validator: radicado_23_digitos");
        TypologyRegistry registry = load(yaml);
        assertThat(registry.active("EC").orElseThrow().version()).isEqualTo(2);
        assertThat(registry.find("EC", 1)).isPresent();
        assertThat(registry.find("EC", 1).orElseThrow().version()).isEqualTo(1);
    }

    @Test
    void ac05_versionDuplicadaSeRechaza() {
        String yaml = typology("EC", 1, "", "validator: radicado_23_digitos") + "\n"
            + typology("EC", 1, "", "validator: radicado_23_digitos");
        assertThatThrownBy(() -> load(yaml)).isInstanceOf(InvalidTypologyException.class)
            .hasMessageContaining("duplicada");
    }

    @Test
    void ac06_tablasModelanArrayDeObjetosConSubcampos() {
        TableDef demandados = TypologyRegistry.loadDefault(VALIDATORS).active("EJ").orElseThrow()
            .table("demandados").orElseThrow();
        assertThat(demandados.fields()).extracting(FieldDef::name)
            .containsExactly("nombre", "tipo_identificacion", "numero_identificacion", "monto", "productos");
    }

    @Test
    void ac07_tiposDeDatoSeExponen() {
        TypologyDef ec = TypologyRegistry.loadDefault(VALIDATORS).active("EC").orElseThrow();
        assertThat(ec.field("fecha_oficio").orElseThrow().type()).isEqualTo(FieldType.DATE);
        assertThat(ec.field("monto_numeros").orElseThrow().type()).isEqualTo(FieldType.DECIMAL);
        assertThat(ec.field("radicado").orElseThrow().type()).isEqualTo(FieldType.STRING);
    }

    @Test
    void ac08_tipologiaInactivaNoEstaDisponible() {
        TypologyRegistry registry = TypologyRegistry.loadDefault(VALIDATORS);
        registry.setActive("EC", false);
        assertThat(registry.active("EC")).isEmpty();
        assertThat(registry.activeDefinitions()).extracting(TypologyDef::code).doesNotContain("EC");
        registry.setActive("EC", true);
        assertThat(registry.active("EC")).isPresent();
    }
}
