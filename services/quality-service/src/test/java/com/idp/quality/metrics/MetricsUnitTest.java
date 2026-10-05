package com.idp.quality.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.quality.metrics.DriftDetector.Day;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Muestreo ciego, deteccion de deriva y percentiles (puros, sin Spring). */
class MetricsUnitTest {

    @Test
    void muestreoCiegoRespetaLaTasaConfigurada() {
        BlindSampler s = new BlindSampler("semilla", 0.02, Map.of());
        UUID tenant = UUID.randomUUID();
        int n = 50_000;
        int hits = 0;
        for (int i = 0; i < n; i++) {
            if (s.shouldSample(tenant, UUID.randomUUID(), "EC")) {
                hits++;
            }
        }
        assertThat(hits / (double) n).isBetween(0.015, 0.025);
    }

    @Test
    void muestreoEsDeterministaPorOficioYDependeDeLaSemilla() {
        UUID tenant = UUID.randomUUID();
        BlindSampler a = new BlindSampler("semilla-a", 0.5, Map.of());
        BlindSampler a2 = new BlindSampler("semilla-a", 0.5, Map.of());
        BlindSampler b = new BlindSampler("semilla-b", 0.5, Map.of());
        int differ = 0;
        for (int i = 0; i < 500; i++) {
            UUID doc = UUID.randomUUID();
            assertThat(a.shouldSample(tenant, doc, "EC")).isEqualTo(a2.shouldSample(tenant, doc, "EC"));
            if (a.shouldSample(tenant, doc, "EC") != b.shouldSample(tenant, doc, "EC")) {
                differ++;
            }
        }
        assertThat(differ).isPositive();
    }

    @Test
    void tasaPorTipologiaSobreescribeLaGlobalYLosExtremosSonExactos() {
        BlindSampler s = new BlindSampler("x", 0.0, Map.of("EC", 1.0, "DC", 0.0));
        UUID t = UUID.randomUUID();
        for (int i = 0; i < 100; i++) {
            UUID doc = UUID.randomUUID();
            assertThat(s.shouldSample(t, doc, "EC")).isTrue();
            assertThat(s.shouldSample(t, doc, "DC")).isFalse();
            assertThat(s.shouldSample(t, doc, "EJ")).isFalse();
        }
        assertThat(s.rateFor("EC")).isEqualTo(1.0);
        assertThatThrownBy(() -> new BlindSampler("x", 1.5, Map.of())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BlindSampler("x", 0.1, Map.of("EC", -0.1)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivaPorErrorSilenteSoloConMuestraMinimaYSobreElMaximo() {
        DriftDetector d = new DriftDetector(0.05, 10, 20);
        assertThat(d.evaluate(new Day(0, 0, 20, 1), List.of()).silentErrorDrift()).isFalse(); // 5 % exacto
        assertThat(d.evaluate(new Day(0, 0, 20, 2), List.of()).silentErrorDrift()).isTrue(); // 10 %
        assertThat(d.evaluate(new Day(0, 0, 19, 19), List.of()).silentErrorDrift()).isFalse(); // sin muestra minima
    }

    @Test
    void derivaPorCaidaDeStpMayorADiezPuntos() {
        DriftDetector d = new DriftDetector(0.05, 10, 20);
        List<Day> history = List.of(new Day(100, 85, 0, 0), new Day(100, 85, 0, 0));
        assertThat(d.evaluate(new Day(100, 76, 0, 0), history).stpDrop()).isFalse(); // 9 puntos
        assertThat(d.evaluate(new Day(100, 74, 0, 0), history).stpDrop()).isTrue(); // 11 puntos
        assertThat(d.evaluate(new Day(100, 75, 0, 0), history).stpDrop()).isFalse(); // exactamente 10
        assertThat(d.evaluate(new Day(10, 0, 0, 0), history).stpDrop()).isFalse(); // dia sin muestra minima
        assertThat(d.evaluate(new Day(100, 10, 0, 0), List.of()).stpDrop()).isFalse(); // sin linea base
    }

    @Test
    void percentilPorRangoMasCercanoYMuestraMinima() {
        long[] v = new long[100];
        for (int i = 0; i < 100; i++) {
            v[i] = 100 - i; // desordenado a proposito
        }
        assertThat(Percentile.nearestRank(v, 95, 30).getAsLong()).isEqualTo(95);
        assertThat(Percentile.nearestRank(v, 100, 30).getAsLong()).isEqualTo(100);
        assertThat(Percentile.nearestRank(new long[] {1, 2, 3}, 95, 30)).isEmpty();
        assertThat(Percentile.nearestRank(new long[0], 95, 0)).isEmpty();
    }
}
