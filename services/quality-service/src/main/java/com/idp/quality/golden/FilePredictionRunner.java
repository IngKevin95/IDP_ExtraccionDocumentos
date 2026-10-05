package com.idp.quality.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Adaptador por archivos: lee {@code <dir>/<clave>.json} con las predicciones ya generadas para el par modelo+prompt
 * (formato {@link PredictionRun}). Permite evaluar y hacer replay sin llamadas a LLM desde el servicio.
 */
public final class FilePredictionRunner implements ExtractionRunner {

    private final Path dir;
    private final ObjectMapper mapper;

    public FilePredictionRunner(Path dir, ObjectMapper mapper) {
        this.dir = dir == null ? null : dir.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public List<FieldPrediction> run(String modelPromptKey, List<GoldenDocument> documents) {
        if (dir == null) {
            throw new RunnerException("quality.runner.predictions-dir no configurado");
        }
        Path file = dir.resolve(safeName(modelPromptKey) + ".json").normalize();
        if (!file.startsWith(dir) || !Files.isRegularFile(file)) {
            throw new RunnerException("Sin resultados registrados para el par modelo+prompt");
        }
        try {
            PredictionRun run = mapper.readValue(file.toFile(), PredictionRun.class);
            return run.predicciones() == null ? List.of() : run.predicciones();
        } catch (IOException e) {
            throw new RunnerException("Archivo de predicciones invalido");
        }
    }

    /** Nombre de archivo seguro derivado de la clave (sin separadores de ruta). */
    public static String safeName(String key) {
        return key == null ? "" : key.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
