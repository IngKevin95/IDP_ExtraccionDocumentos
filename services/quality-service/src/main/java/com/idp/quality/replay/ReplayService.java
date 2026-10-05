package com.idp.quality.replay;

import com.idp.quality.golden.EvaluationEngine;
import com.idp.quality.golden.EvaluationResult;
import com.idp.quality.golden.ExtractionRunner;
import com.idp.quality.golden.GoldenDocument;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;

/**
 * Replay de oficios sinteticos con un modelo o prompt nuevo, en paralelo con el vigente. Solo lee el golden set y
 * llama al puerto de ejecucion: no escribe en metricas ni en ninguna estructura de produccion.
 */
public final class ReplayService {

    /** Comparacion candidato contra vigente. {@code regression} es true si baja F1 o empeora el ECE. */
    public record Report(EvaluationResult baseline, EvaluationResult candidate, double deltaF1,
                         double deltaPrecision, double deltaRecall, double deltaEce, boolean regression) {
    }

    private static final double TOLERANCE = 1e-9;

    private final ExtractionRunner runner;
    private final EvaluationEngine engine;
    private final Executor executor;

    public ReplayService(ExtractionRunner runner, EvaluationEngine engine, Executor executor) {
        this.runner = runner;
        this.engine = engine;
        this.executor = executor;
    }

    public Report replay(String baselineKey, String candidateKey, List<GoldenDocument> docs) {
        CompletableFuture<EvaluationResult> baseline = CompletableFuture.supplyAsync(
            () -> engine.evaluate(baselineKey, docs, runner.run(baselineKey, docs)), executor);
        CompletableFuture<EvaluationResult> candidate = CompletableFuture.supplyAsync(
            () -> engine.evaluate(candidateKey, docs, runner.run(candidateKey, docs)), executor);
        try {
            EvaluationResult b = baseline.join();
            EvaluationResult c = candidate.join();
            boolean regression = c.f1() + TOLERANCE < b.f1() || c.eceCalibrated() > b.eceCalibrated() + TOLERANCE;
            return new Report(b, c, c.f1() - b.f1(), c.precision() - b.precision(), c.recall() - b.recall(),
                c.eceCalibrated() - b.eceCalibrated(), regression);
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }
}
