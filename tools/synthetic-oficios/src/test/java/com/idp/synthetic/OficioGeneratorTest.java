package com.idp.synthetic;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.extraction.security.PromptInjectionDetector;
import com.idp.extraction.validation.FieldValidator;
import com.idp.extraction.validation.TableSumValidator;
import com.idp.extraction.validation.ValidationContext;
import com.idp.extraction.validation.ValidatorRegistry;
import com.idp.synthetic.OficioData.TipoDefecto;
import com.idp.synthetic.OficioGenerator.Plan;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class OficioGeneratorTest {

    private static final OficioGenerator GEN = new OficioGenerator();
    private static final ValidatorRegistry VALIDADORES = ValidatorRegistry.defaults();

    @Test
    void mismaSemillaMismoOficioYOtraSemillaDistinto() {
        for (Tipo t : Tipo.values()) {
            OficioData a = GEN.generar(t, 42, 3, Plan.CICLICO);
            assertThat(GEN.generar(t, 42, 3, Plan.CICLICO)).isEqualTo(a);
            assertThat(GEN.generar(t, 43, 3, Plan.CICLICO)).isNotEqualTo(a);
            assertThat(GEN.generar(t, 42, 4, Plan.CICLICO)).isNotEqualTo(a);
        }
    }

    @Test
    void camposDelOficioSonLosDeLaTipologiaYTrae2a6Demandados() {
        for (Tipo t : Tipo.values()) {
            for (int i = 1; i <= 10; i++) {
                OficioData o = GEN.generar(t, 7, i, Plan.CICLICO);
                assertThat(o.campos().keySet()).containsExactlyElementsOf(GEN.tipologia(t).campos().keySet());
                assertThat(o.demandados().size()).isBetween(2, 6);
                assertThat(o.campo("tipo_medida")).isEqualTo(t.medida());
                assertThat(o.campo("radicado")).matches("\\d{23}");
                // Especialidad reservada 99 (posiciones 6-7): no corresponde a ninguna jurisdiccion real.
                assertThat(o.campo("radicado").substring(5, 7)).isEqualTo("99");
                for (java.util.Map<String, String> d : o.demandados()) {
                    String nombre = d.get("nombre");
                    boolean empresa = nombre.endsWith("S.A.S.") || nombre.endsWith("S.A.") || nombre.endsWith("Ltda.");
                    assertThat(empresa ? nombre.contains("Ficticia")
                        : Datos.NOMBRES.contains(nombre.split(" ")[0])).as(nombre).isTrue();
                }
            }
        }
    }

    @Test
    void catalogosDeNombresSonInventados() {
        assertThat(Datos.EMPRESA_APELLIDO).allMatch(e -> e.startsWith("Ficticia"));
        assertThat(Datos.NOMBRES).doesNotContain("Carlos", "Andrea", "Luis", "Marta", "Jorge");
        assertThat(Datos.APELLIDOS).doesNotContain("Rojas", "Vargas", "Restrepo", "Ospina");
    }

    @Test
    void planCiclicoInyectaUnDefectoPorIndiceYLimpioNinguno() {
        TipoDefecto[] esperado = {null, TipoDefecto.MONTO_INCONSISTENTE, TipoDefecto.TABLA_NO_SUMA,
            TipoDefecto.INJECTION_PROMPT, TipoDefecto.CAMPO_FALTANTE};
        for (Tipo t : Tipo.values()) {
            for (int i = 1; i <= 10; i++) {
                OficioData o = GEN.generar(t, 9, i, Plan.CICLICO);
                TipoDefecto e = esperado[(i - 1) % 5];
                assertThat(o.defectos().stream().map(OficioData.Defecto::tipo).toList())
                    .isEqualTo(e == null ? java.util.List.of() : java.util.List.of(e));
                assertThat(GEN.generar(t, 9, i, Plan.LIMPIO).defectos()).isEmpty();
            }
        }
    }

    @Test
    void validadoresDeExtraccionCoincidenConLoEsperado() {
        for (Tipo t : Tipo.values()) {
            for (int i = 1; i <= 15; i++) {
                OficioData o = GEN.generar(t, 21, i, Plan.CICLICO);
                assertThat(resultadosReales(o)).as(o.id()).isEqualTo(
                    GroundTruth.validadoresEsperados(o, GEN.tipologia(t)));
            }
        }
    }

    @Test
    void defectosProvocanFallosDeterministasYLosLimpiosPasanTodo() {
        OficioData limpio = GEN.generar(Tipo.EC, 3, 1, Plan.CICLICO);
        assertThat(resultadosReales(limpio).values()).containsOnly(GroundTruth.PASS);
        OficioData monto = GEN.generar(Tipo.EC, 3, 2, Plan.CICLICO);
        assertThat(resultadosReales(monto)).containsEntry("monto_numeros_letras", GroundTruth.FAIL);
        OficioData tabla = GEN.generar(Tipo.EJ, 3, 3, Plan.CICLICO);
        assertThat(resultadosReales(tabla)).containsEntry(GroundTruth.SUMA_TABLA, GroundTruth.FAIL)
            .containsEntry("monto_numeros_letras", GroundTruth.PASS);
        OficioData faltante = GEN.generar(Tipo.DJ, 3, 5, Plan.CICLICO);
        assertThat(faltante.defectos()).singleElement()
            .satisfies(d -> assertThat(faltante.campo(d.campo())).isNull());
    }

    @Test
    void inyeccionDePromptEsDetectadaConLaReglaEsperada() throws Exception {
        PromptInjectionDetector detector = new PromptInjectionDetector();
        OficioPdfWriter writer = new OficioPdfWriter();
        for (Tipo t : Tipo.values()) {
            for (int i = 1; i <= 10; i++) {
                OficioData o = GEN.generar(t, 5, i, Plan.CICLICO);
                String texto;
                try (var doc = writer.escribir(o)) {
                    texto = OficioPdfWriter.texto(doc);
                }
                if (o.inyeccion() != null) {
                    assertThat(detector.scan(texto)).as(o.id()).contains(o.inyeccion().regla());
                } else {
                    assertThat(detector.scan(texto)).as(o.id()).isEmpty();
                }
            }
        }
    }

    /** Ejecuta los validadores reales de extraction-service sobre los valores del oficio, agregados por id. */
    static Map<String, String> resultadosReales(OficioData o) {
        TipologiaDef def = GEN.tipologia(o.tipo());
        Map<String, String> doc = new LinkedHashMap<>();
        o.campos().forEach((k, v) -> {
            if (v != null) {
                doc.put(k, v);
            }
        });
        Map<String, String> r = new LinkedHashMap<>();
        for (TipologiaDef.Campo c : def.campos().values()) {
            if (c.validador() != null) {
                FieldValidator v = VALIDADORES.find(c.validador()).orElseThrow();
                boolean ok = v.validate(o.campo(c.nombre()), ValidationContext.of(doc, Datos.FECHA_REFERENCIA)).passed();
                r.merge(c.validador(), ok ? GroundTruth.PASS : GroundTruth.FAIL, OficioGeneratorTest::peor);
            }
        }
        for (TipologiaDef.Campo c : def.columnasDemandados().values()) {
            if (c.validador() != null) {
                FieldValidator v = VALIDADORES.find(c.validador()).orElseThrow();
                for (Map<String, String> fila : o.demandados()) {
                    boolean ok = v.validate(fila.get(c.nombre()),
                        new ValidationContext(doc, fila, Datos.FECHA_REFERENCIA)).passed();
                    r.merge(c.validador(), ok ? GroundTruth.PASS : GroundTruth.FAIL, OficioGeneratorTest::peor);
                }
            }
        }
        boolean suma = new TableSumValidator().validate(o.demandados(), "monto", o.campo("monto_numeros")).passed();
        r.put(GroundTruth.SUMA_TABLA, suma ? GroundTruth.PASS : GroundTruth.FAIL);
        return r;
    }

    private static String peor(String a, String b) {
        return GroundTruth.FAIL.equals(a) || GroundTruth.FAIL.equals(b) ? GroundTruth.FAIL : GroundTruth.PASS;
    }
}
