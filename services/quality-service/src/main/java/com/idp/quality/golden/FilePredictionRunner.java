package com.idp.quality.golden;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

/**
 * Adaptador por archivos: lee {@code <dir>/<tenantId>/<clave>.json} con las predicciones ya generadas para el par
 * modelo+prompt (formato {@link PredictionRun}). Cada tenant tiene su propio subdirectorio, no se siguen enlaces
 * simbolicos, el JSON tiene tope de tamano y la clave no puede escapar del directorio. Permite evaluar y hacer
 * replay sin llamadas a LLM desde el servicio.
 */
public final class FilePredictionRunner implements ExtractionRunner {

    /** Tope de tamano del JSON de predicciones. */
    public static final int MAX_BYTES = 10 * 1024 * 1024;

    private static final String KEY = "[A-Za-z0-9._:/-]{1,120}";

    private final Path dir;
    private final ObjectMapper mapper;

    public FilePredictionRunner(Path dir, ObjectMapper mapper) {
        this.dir = dir == null ? null : dir.toAbsolutePath().normalize();
        this.mapper = mapper;
    }

    @Override
    public List<FieldPrediction> run(UUID tenantId, String modelPromptKey, List<GoldenDocument> documents) {
        if (dir == null) {
            throw new RunnerException("quality.runner.predictions-dir no configurado");
        }
        if (tenantId == null || modelPromptKey == null || !modelPromptKey.matches(KEY)
                || modelPromptKey.contains("..")) {
            throw new RunnerException("Clave de modelo+prompt invalida");
        }
        Path tenantDir = dir.resolve(tenantId.toString()).normalize();
        Path file = tenantDir.resolve(safeName(modelPromptKey) + ".json").normalize();
        if (!tenantDir.startsWith(dir) || !file.startsWith(tenantDir)
                || !Files.isDirectory(tenantDir, LinkOption.NOFOLLOW_LINKS)
                || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
            throw new RunnerException("Sin resultados registrados para el par modelo+prompt");
        }
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = in.readNBytes(MAX_BYTES + 1);
            if (bytes.length > MAX_BYTES) {
                throw new RunnerException("Archivo de predicciones demasiado grande");
            }
            PredictionRun run = mapper.readValue(bytes, PredictionRun.class);
            return run.predicciones() == null ? List.of() : run.predicciones();
        } catch (IOException | UnsupportedOperationException e) {
            throw new RunnerException("Archivo de predicciones invalido");
        }
    }

    /** Nombre de archivo seguro derivado de la clave (sin separadores de ruta). */
    public static String safeName(String key) {
        return key == null ? "" : key.replaceAll("[^A-Za-z0-9._-]", "_");
    }
}
