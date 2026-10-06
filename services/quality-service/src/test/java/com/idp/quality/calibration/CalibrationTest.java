package com.idp.quality.calibration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;

/** Calibracion isotonica, ECE y umbrales por precision objetivo (fiabilidad AC-01/AC-07, ADR-0012). */
class CalibrationTest {

    @Test
    void isotonicaEsMonotonaYPoolaViolaciones() {
        // 0.2 acierta, 0.4 falla, 0.6 acierta, 0.8 falla, 1.0 acierta: PAV agrupa en tramos no decrecientes.
        IsotonicCalibrator c = IsotonicCalibrator.fit(new double[] {0.2, 0.4, 0.6, 0.8, 1.0},
            new boolean[] {true, false, true, false, true});
        double prev = -1;
        for (double s = 0; s <= 1.0001; s += 0.05) {
            double p = c.predict(s);
            assertThat(p).isBetween(0.0, 1.0);
            assertThat(p).isGreaterThanOrEqualTo(prev - 1e-12);
            prev = p;
        }
    }

    @Test
    void isotonicaTratasScoresEmpatadosConUnaMismaSalida() {
        IsotonicCalibrator c = IsotonicCalibrator.fit(new double[] {0.9, 0.9, 0.9, 0.9, 0.5},
            new boolean[] {true, true, true, false, false});
        assertThat(c.predict(0.9)).isEqualTo(0.75, within(1e-12));
        assertThat(c.predict(0.5)).isEqualTo(0.0, within(1e-12));
    }

    @Test
    void sinDatosDevuelveElScoreAcotado() {
        IsotonicCalibrator c = IsotonicCalibrator.fit(new double[0], new boolean[0]);
        assertThat(c.predict(0.7)).isEqualTo(0.7);
        assertThat(c.predict(3.0)).isEqualTo(1.0);
    }

    @Test
    void curvaSePuedeSerializarYReconstruir() {
        IsotonicCalibrator c = IsotonicCalibrator.fit(new double[] {0.1, 0.5, 0.9}, new boolean[] {false, true, true});
        IsotonicCalibrator back = IsotonicCalibrator.of(c.points());
        assertThat(back.predict(0.3)).isEqualTo(c.predict(0.3));
        assertThatThrownBy(() -> IsotonicCalibrator.of(List.of(new IsotonicCalibrator.Point(0.5, 0.9),
            new IsotonicCalibrator.Point(0.6, 0.1)))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void eceEsCeroConCalibracionPerfectYGrandeConSobreconfianza() {
        // Todo con confianza 0.9 y exactitud real 0.9: ECE 0.
        boolean[] ok = new boolean[10];
        double[] p = new double[10];
        for (int i = 0; i < 10; i++) {
            ok[i] = i != 0;
            p[i] = 0.9;
        }
        assertThat(CalibrationMetrics.ece(p, ok, 10)).isEqualTo(0.0, within(1e-12));
        // Confianza 0.99 con exactitud real 0.5: ECE 0.49.
        double[] over = new double[10];
        boolean[] half = new boolean[10];
        for (int i = 0; i < 10; i++) {
            over[i] = 0.99;
            half[i] = i % 2 == 0;
        }
        assertThat(CalibrationMetrics.ece(over, half, 10)).isEqualTo(0.49, within(1e-12));
        assertThat(CalibrationMetrics.ece(new double[0], new boolean[0], 10)).isZero();
    }

    @Test
    void laCalibracionIsotonicaReduceElEceDeUnModeloSobreconfiado() {
        Random rnd = new Random(42);
        int n = 2000;
        double[] raw = new double[n];
        boolean[] ok = new boolean[n];
        for (int i = 0; i < n; i++) {
            raw[i] = 0.5 + rnd.nextDouble() * 0.5; // confianza declarada 0.5 a 1
            double trueProb = 0.2 + (raw[i] - 0.5) * 1.2; // la realidad es bastante peor
            ok[i] = rnd.nextDouble() < trueProb;
        }
        IsotonicCalibrator c = IsotonicCalibrator.fit(raw, ok);
        double[] cal = new double[n];
        for (int i = 0; i < n; i++) {
            cal[i] = c.predict(raw[i]);
        }
        double before = CalibrationMetrics.ece(raw, ok, 10);
        double after = CalibrationMetrics.ece(cal, ok, 10);
        assertThat(before).isGreaterThan(0.2);
        assertThat(after).isLessThan(0.05).isLessThan(before);
    }

    @Test
    void umbralAutoEsElScoreMasBajoQueCumpleLaPrecisionObjetivo() {
        // 10 muestras ordenadas: las 6 mas altas son correctas, luego mezcla.
        double[] s = {0.99, 0.95, 0.9, 0.85, 0.8, 0.75, 0.6, 0.5, 0.4, 0.3};
        boolean[] ok = {true, true, true, true, true, true, false, true, false, false};
        ThresholdSelector.Thresholds t = ThresholdSelector.select(s, ok, 0.95, 0.7, 5);
        assertThat(t.attainable()).isTrue();
        assertThat(t.tauAuto()).isEqualTo(0.75);
        assertThat(t.precisionAtAuto()).isEqualTo(1.0);
        assertThat(t.coverageAtAuto()).isEqualTo(0.6);
        // precision acumulada >= 0.7 hasta 0.5 (7 de 8 = 0.875) pero 0.4 baja a 7/9 = 0.78 y 0.3 a 0.7 exacto.
        assertThat(t.tauRevisar()).isEqualTo(0.3);
        assertThat(t.tauRevisar()).isLessThanOrEqualTo(t.tauAuto());
    }

    @Test
    void sinUmbralQueCumplaNadaSeAutoApruebaYConMuestraInsuficienteTampoco() {
        double[] s = {0.9, 0.8, 0.7, 0.6, 0.5, 0.4};
        boolean[] ok = {false, true, false, true, false, true};
        ThresholdSelector.Thresholds t = ThresholdSelector.select(s, ok, 0.95, 0.6, 3);
        assertThat(t.attainable()).isFalse();
        assertThat(t.tauAuto()).isGreaterThan(1.0);

        ThresholdSelector.Thresholds few = ThresholdSelector.select(new double[] {1.0, 1.0}, new boolean[] {true, true},
            0.9, 0.5, 10);
        assertThat(few.attainable()).isFalse();
        assertThat(few.tauAuto()).isGreaterThan(1.0);
    }

    @Test
    void rechazaObjetivosInvalidos() {
        assertThatThrownBy(() -> ThresholdSelector.select(new double[] {1}, new boolean[] {true}, 0.5, 0.9, 1))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
