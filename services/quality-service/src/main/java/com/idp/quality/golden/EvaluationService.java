package com.idp.quality.golden;

import com.idp.quality.replay.ReplayService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Orquesta las corridas asincronas sobre el golden set del tenant: evaluacion del par modelo+prompt vigente (con
 * calibracion y umbrales) y replay de un candidato en paralelo con el vigente. Ninguna escribe en metricas de
 * produccion (AC-04).
 */
@Service
public class EvaluationService {

    private static final Logger LOG = LoggerFactory.getLogger(EvaluationService.class);

    private final GoldenRepository repo;
    private final ExtractionRunner runner;
    private final EvaluationEngine engine;
    private final ReplayService replay;
    private final Executor executor;

    public EvaluationService(GoldenRepository repo, ExtractionRunner runner, EvaluationEngine engine,
                             ReplayService replay, @org.springframework.beans.factory.annotation.Qualifier(
                                 "qualityExecutor") Executor executor) {
        this.repo = repo;
        this.runner = runner;
        this.engine = engine;
        this.replay = replay;
        this.executor = executor;
    }

    /** Encola la evaluacion y devuelve de inmediato el id del job. */
    public UUID submitEvaluation(UUID tenant, String modelPromptKey) {
        UUID id = UUID.randomUUID();
        repo.createEvaluation(id, tenant, "EVALUATION", modelPromptKey);
        executor.execute(() -> runEvaluation(tenant, id, modelPromptKey));
        return id;
    }

    public UUID submitReplay(UUID tenant, String baselineKey, String candidateKey) {
        UUID id = UUID.randomUUID();
        repo.createEvaluation(id, tenant, "REPLAY", candidateKey);
        executor.execute(() -> runReplay(tenant, id, baselineKey, candidateKey));
        return id;
    }

    void runEvaluation(UUID tenant, UUID id, String key) {
        try {
            repo.markRunning(id);
            List<GoldenDocument> docs = repo.list(tenant);
            if (docs.isEmpty()) {
                throw new ExtractionRunner.RunnerException("El golden set esta vacio");
            }
            EvaluationResult result = engine.evaluate(key, docs, runner.run(key, docs));
            repo.complete(id, result);
            repo.saveCalibration(id, tenant, key, result.calibration());
            repo.replaceThresholds(tenant, key, id, result.thresholds());
        } catch (RuntimeException e) {
            fail(id, e);
        }
    }

    void runReplay(UUID tenant, UUID id, String baselineKey, String candidateKey) {
        try {
            repo.markRunning(id);
            List<GoldenDocument> docs = repo.list(tenant);
            if (docs.isEmpty()) {
                throw new ExtractionRunner.RunnerException("El golden set esta vacio");
            }
            repo.completeReplay(id, replay.replay(baselineKey, candidateKey, docs));
        } catch (RuntimeException e) {
            fail(id, e);
        }
    }

    private void fail(UUID id, RuntimeException e) {
        LOG.warn("Corrida {} fallida: {}", id, e.getClass().getSimpleName());
        repo.fail(id, e instanceof ExtractionRunner.RunnerException ? e.getMessage() : "Error interno");
    }
}
