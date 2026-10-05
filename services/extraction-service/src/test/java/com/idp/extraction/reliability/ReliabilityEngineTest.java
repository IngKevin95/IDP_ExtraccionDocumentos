package com.idp.extraction.reliability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.idp.extraction.core.Evidence;
import com.idp.extraction.core.ExtractedValue;
import com.idp.extraction.reliability.ReliabilityEngine.Decision;
import com.idp.extraction.reliability.ReliabilityEngine.FieldScore;
import com.idp.extraction.typology.FieldDef;
import com.idp.extraction.typology.FieldType;
import com.idp.extraction.validation.ValidationResult;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Especificacion fiabilidad AC-01 a AC-08 (la parte de muestreo y gate de CI vive en quality-service). */
class ReliabilityEngineTest {

    private static final Evidence EVIDENCE = new Evidence(1, "texto literal", List.of(0.1, 0.1, 0.2, 0.05));
    private static final FieldDef CRITICAL = new FieldDef("radicado", FieldType.STRING, "d", true,
        "radicado_23_digitos", 0.95, 0.70);
    private static final FieldDef PLAIN = new FieldDef("ciudad", FieldType.STRING, "d", false, null, 0.90, 0.60);
    private final ReliabilityEngine engine = new ReliabilityEngine(new IdentityCalibrator());

    private static ExtractedValue value(Double confidence, Evidence evidence) {
        return new ExtractedValue("valor", confidence, evidence);
    }

    @Test
    void ac01_validadorFallidoPenalizaAMenosDeTauRevisarAunConConfianzaAlta() {
        FieldScore s = engine.score(CRITICAL, value(0.9, EVIDENCE), List.of(ValidationResult.fail("X", "x")),
            Grounding.Status.FOUND);
        assertThat(s.score()).isCloseTo(0.2, within(1e-9));
        assertThat(s.validatorFailed()).isTrue();
        assertThat(engine.route(CRITICAL, s)).isEqualTo(Decision.REVIEW);
    }

    @Test
    void ac02_scoreEnFranjaDeDudaVaACascadaYSuperaTauAutoTrasLaSegundaPasada() {
        FieldScore first = engine.score(PLAIN, value(0.75, EVIDENCE), List.of(), Grounding.Status.NOT_REQUIRED);
        assertThat(engine.route(PLAIN, first)).isEqualTo(Decision.CASCADE);
        FieldScore second = engine.score(PLAIN, value(0.95, EVIDENCE), List.of(), Grounding.Status.NOT_REQUIRED);
        assertThat(engine.route(PLAIN, second)).isEqualTo(Decision.AUTO);
    }

    @Test
    void ac03_scoreQuePersisteEnFranjaDudosaQuedaSinResolverAutomaticamente() {
        FieldScore s = engine.score(PLAIN, value(0.8, EVIDENCE), List.of(), Grounding.Status.NOT_REQUIRED);
        assertThat(engine.route(PLAIN, s)).isEqualTo(Decision.CASCADE);
        assertThat(s.score()).isLessThan(PLAIN.umbralAuto());
    }

    @Test
    void ac05_umbralDeCampoCriticoSeRespeta() {
        FieldDef strict = new FieldDef("monto", FieldType.DECIMAL, "d", true, null, 0.99, 0.80);
        FieldScore s = engine.score(strict, value(0.98, EVIDENCE), List.of(), Grounding.Status.NOT_REQUIRED);
        assertThat(s.score()).isCloseTo(0.98, within(1e-9));
        assertThat(engine.route(strict, s)).isEqualTo(Decision.CASCADE);
    }

    @Test
    void ac06_sinEvidenciaElScoreDecreceYObligaCascada() {
        FieldScore noEvidence = engine.score(CRITICAL, value(0.99, null), List.of(), Grounding.Status.NOT_REQUIRED);
        assertThat(noEvidence.evidenceMissing()).isTrue();
        assertThat(noEvidence.flags()).contains("AUSENCIA_DE_EVIDENCIA");
        assertThat(noEvidence.score()).isLessThan(CRITICAL.umbralAuto()).isGreaterThanOrEqualTo(CRITICAL.umbralRevisar());
        assertThat(engine.route(CRITICAL, noEvidence)).isEqualTo(Decision.CASCADE);
        Evidence sinBbox = new Evidence(1, "cita", null);
        assertThat(engine.score(CRITICAL, value(0.99, sinBbox), List.of(), Grounding.Status.NOT_REQUIRED)
            .evidenceMissing()).isTrue();
        Evidence sinCita = new Evidence(1, " ", List.of(0.0, 0.0, 1.0, 1.0));
        assertThat(engine.score(CRITICAL, value(0.99, sinCita), List.of(), Grounding.Status.NOT_REQUIRED)
            .evidenceMissing()).isTrue();
    }

    @Test
    void ac08_sinConfianzaDelModeloSeDegradaYSeApoyaEnGroundingYValidadores() {
        List<ValidationResult> pass = List.of(ValidationResult.pass());
        FieldScore full = engine.score(CRITICAL, value(null, EVIDENCE), pass, Grounding.Status.FOUND);
        assertThat(full.confidenceMissing()).isTrue();
        assertThat(full.score()).isCloseTo(0.9, within(1e-9));
        assertThat(engine.route(CRITICAL, full)).isEqualTo(Decision.CASCADE);
        FieldScore bare = engine.score(PLAIN, value(null, EVIDENCE), List.of(), Grounding.Status.NOT_REQUIRED);
        assertThat(bare.score()).isCloseTo(0.5, within(1e-9));
        assertThat(engine.route(PLAIN, bare)).isEqualTo(Decision.REVIEW);
    }

    @Test
    void textoNoHalladoEnCapaNativaDerribaElScore() {
        FieldScore s = engine.score(CRITICAL, value(0.99, EVIDENCE), List.of(ValidationResult.pass()),
            Grounding.Status.NOT_FOUND);
        assertThat(s.score()).isLessThan(CRITICAL.umbralRevisar());
        assertThat(s.flags()).contains("TEXTO_NO_HALLADO_EN_CAPA_NATIVA");
        assertThat(engine.route(CRITICAL, s)).isEqualTo(Decision.REVIEW);
    }

    @Test
    void tresTramosPorUmbrales() {
        assertThat(route(0.95)).isEqualTo(Decision.AUTO);
        assertThat(route(0.97)).isEqualTo(Decision.AUTO);
        assertThat(route(0.80)).isEqualTo(Decision.CASCADE);
        assertThat(route(0.70)).isEqualTo(Decision.CASCADE);
        assertThat(route(0.69)).isEqualTo(Decision.REVIEW);
    }

    private Decision route(double conf) {
        FieldDef def = new FieldDef("c", FieldType.STRING, "d", false, null, 0.95, 0.70);
        return engine.route(def, engine.score(def, value(conf, EVIDENCE), List.of(), Grounding.Status.NOT_REQUIRED));
    }

    @Test
    void valorAusenteTieneScoreCero() {
        FieldScore s = engine.score(CRITICAL, null, List.of(), Grounding.Status.NOT_REQUIRED);
        assertThat(s.score()).isZero();
        assertThat(s.flags()).contains("VALOR_AUSENTE");
    }

    // ---- calibradores --------------------------------------------------------------------------------

    @Test
    void identidadAcotaAlRango() {
        Calibrator c = new IdentityCalibrator();
        assertThat(c.calibrate("string", 0.42)).isEqualTo(0.42);
        assertThat(c.calibrate("string", 1.7)).isEqualTo(1.0);
        assertThat(c.calibrate("string", -1)).isZero();
        assertThat(c.id()).isEqualTo("identity");
    }

    @Test
    void isotonicaInterpolaYSeCargaDesdeJson() {
        String json = """
            {"id":"iso-test","curves":{
              "default":{"x":[0,0.5,1],"y":[0,0.4,1]},
              "decimal":{"x":[0,1],"y":[0.1,0.9]}}}
            """;
        IsotonicCalibrator c = IsotonicCalibrator.fromJson(new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
        assertThat(c.id()).isEqualTo("iso-test");
        assertThat(c.calibrate("string", 0.25)).isCloseTo(0.2, within(1e-9));
        assertThat(c.calibrate("string", 0.75)).isCloseTo(0.7, within(1e-9));
        assertThat(c.calibrate("string", 0.5)).isCloseTo(0.4, within(1e-9));
        assertThat(c.calibrate("string", 2.0)).isCloseTo(1.0, within(1e-9));
        assertThat(c.calibrate("decimal", 0.5)).isCloseTo(0.5, within(1e-9));
        assertThat(c.calibrate("decimal", 0.0)).isCloseTo(0.1, within(1e-9));
    }

    @Test
    void isotonicaMonotonaYValidaSuCurva() {
        IsotonicCalibrator c = new IsotonicCalibrator("i", Map.of("default",
            new IsotonicCalibrator.Curve(new double[] {0, 0.3, 0.6, 1}, new double[] {0, 0.1, 0.1, 1})));
        double prev = -1;
        for (int i = 0; i <= 100; i++) {
            double v = c.calibrate("string", i / 100.0);
            assertThat(v).isGreaterThanOrEqualTo(prev);
            prev = v;
        }
        assertThatThrownBy(() -> new IsotonicCalibrator.Curve(new double[] {0, 1}, new double[] {0.8, 0.2}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IsotonicCalibrator.Curve(new double[] {0.5, 0.5}, new double[] {0, 1}))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IsotonicCalibrator("x", Map.of()))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void laCalibracionSeAplicaAntesDeLasReglasDuras() {
        IsotonicCalibrator pessimistic = new IsotonicCalibrator("p", Map.of("default",
            new IsotonicCalibrator.Curve(new double[] {0, 1}, new double[] {0, 0.5})));
        ReliabilityEngine e = new ReliabilityEngine(pessimistic);
        FieldScore s = e.score(PLAIN, value(1.0, EVIDENCE), List.of(), Grounding.Status.NOT_REQUIRED);
        assertThat(s.score()).isCloseTo(0.5, within(1e-9));
        assertThat(e.route(PLAIN, s)).isEqualTo(Decision.REVIEW);
    }

    // ---- grounding -----------------------------------------------------------------------------------

    private static final String TEXT = "OFICIO 11001-31-03-005-2024-00123-00. Embargo por $15.000.000,00 pesos. "
        + "C.C. 1.234.567 a favor de Maria Pérez.";

    @Test
    void groundingExactoTrasNormalizacion() {
        Grounding g = new Grounding();
        FieldDef radicado = CRITICAL;
        FieldDef monto = new FieldDef("monto_numeros", FieldType.DECIMAL, "d", true, null, 0.95, 0.7);
        FieldDef cedula = new FieldDef("numero_identificacion", FieldType.STRING, "d", true, null, 0.95, 0.7);
        assertThat(g.check(radicado, new ExtractedValue("11001310300520240012300", 0.9, EVIDENCE), TEXT))
            .isEqualTo(Grounding.Status.FOUND);
        assertThat(g.check(radicado, new ExtractedValue("11001310300520240012399", 0.9, EVIDENCE), TEXT))
            .isEqualTo(Grounding.Status.NOT_FOUND);
        assertThat(g.check(monto, new ExtractedValue("15000000", 0.9, EVIDENCE), TEXT)).isEqualTo(Grounding.Status.FOUND);
        assertThat(g.check(monto, new ExtractedValue("1500000", 0.9, EVIDENCE), TEXT)).isEqualTo(Grounding.Status.NOT_FOUND);
        assertThat(g.check(monto, new ExtractedValue("150000000", 0.9, EVIDENCE), TEXT)).isEqualTo(Grounding.Status.NOT_FOUND);
        assertThat(g.check(cedula, new ExtractedValue("1234567", 0.9, EVIDENCE), TEXT)).isEqualTo(Grounding.Status.FOUND);
        assertThat(g.check(cedula, new ExtractedValue("María Pérez", 0.9, EVIDENCE), TEXT)).isEqualTo(Grounding.Status.FOUND);
    }

    @Test
    void groundingSoloAplicaACriticosConCapaDeTexto() {
        Grounding g = new Grounding();
        assertThat(g.check(PLAIN, new ExtractedValue("Bogota", 0.9, EVIDENCE), TEXT)).isEqualTo(Grounding.Status.NOT_REQUIRED);
        assertThat(g.check(CRITICAL, new ExtractedValue("1", 0.9, EVIDENCE), "")).isEqualTo(Grounding.Status.NO_TEXT_LAYER);
        assertThat(g.check(CRITICAL, new ExtractedValue("1", 0.9, EVIDENCE), null)).isEqualTo(Grounding.Status.NO_TEXT_LAYER);
        assertThat(g.check(CRITICAL, new ExtractedValue(null, 0.9, EVIDENCE), TEXT)).isEqualTo(Grounding.Status.NOT_REQUIRED);
    }
}
