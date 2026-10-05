package com.idp.quality.replay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.quality.golden.EvaluationEngine;
import com.idp.quality.golden.ExtractionRunner;
import com.idp.quality.golden.FieldPrediction;
import com.idp.quality.golden.GoldenDocument;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Replay con modelo o prompt nuevo en paralelo con el vigente, sin escribir en produccion. */
class ReplayServiceTest {

    private static List<GoldenDocument> docs(int n) {
        List<GoldenDocument> out = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            out.add(new GoldenDocument(UUID.randomUUID(), "d-" + i, "d-" + i, "EC", List.of(),
                Map.of("monto", "M" + i)));
        }
        return out;
    }

    private static List<FieldPrediction> predict(List<GoldenDocument> docs, int wrongEvery) {
        List<FieldPrediction> out = new ArrayList<>();
        for (int i = 0; i < docs.size(); i++) {
            boolean ok = wrongEvery == 0 || i % wrongEvery != 0;
            out.add(new FieldPrediction(docs.get(i).key(), "monto", ok ? "M" + i : "mal", 0.9));
        }
        return out;
    }

    private final EvaluationEngine engine = new EvaluationEngine(new EvaluationEngine.Params(0.9, 0.7, 10, 10));

    @Test
    void ejecutaVigenteYCandidatoEnParaleloYComparaMetricas() throws Exception {
        CountDownLatch bothRunning = new CountDownLatch(2);
        ExtractionRunner runner = (key, documents) -> {
            bothRunning.countDown();
            try {
                // Si no corrieran en paralelo, el primero esperaria en vano al segundo.
                if (!bothRunning.await(5, TimeUnit.SECONDS)) {
                    throw new ExtractionRunner.RunnerException("no corrieron en paralelo");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return predict(documents, "viejo".equals(key) ? 5 : 0);
        };
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            ReplayService.Report r = new ReplayService(runner, engine, ex).replay("viejo", "nuevo", docs(40));
            assertThat(r.baseline().modelPromptKey()).isEqualTo("viejo");
            assertThat(r.candidate().modelPromptKey()).isEqualTo("nuevo");
            assertThat(r.candidate().f1()).isEqualTo(1.0);
            assertThat(r.deltaF1()).isGreaterThan(0.1);
            assertThat(r.regression()).isFalse();
        }
    }

    @Test
    void marcaRegresionSiElCandidatoEmpeora() {
        ExtractionRunner runner = (key, documents) -> predict(documents, "viejo".equals(key) ? 0 : 3);
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            ReplayService.Report r = new ReplayService(runner, engine, ex).replay("viejo", "peor", docs(30));
            assertThat(r.deltaF1()).isNegative();
            assertThat(r.regression()).isTrue();
        }
    }

    @Test
    void propagaElFalloDeUnaDeLasCorridas() {
        ExtractionRunner runner = (key, documents) -> {
            if ("nuevo".equals(key)) {
                throw new ExtractionRunner.RunnerException("sin resultados");
            }
            return predict(documents, 0);
        };
        try (ExecutorService ex = Executors.newVirtualThreadPerTaskExecutor()) {
            assertThatThrownBy(() -> new ReplayService(runner, engine, ex).replay("viejo", "nuevo", docs(5)))
                .isInstanceOf(ExtractionRunner.RunnerException.class).hasMessage("sin resultados");
        }
    }
}
