package com.idp.quality.config;

import java.util.Map;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Configuracion externa de quality-service. Los secretos (semilla del muestreo) llegan por entorno. */
@ConfigurationProperties(prefix = "quality")
public record QualityProperties(
    @DefaultValue BlindSampling blindSampling,
    @DefaultValue Drift drift,
    @DefaultValue Performance performance,
    @DefaultValue Calibration calibration,
    @DefaultValue GoldenSet goldenSet,
    @DefaultValue Runner runner,
    @DefaultValue Scheduler scheduler,
    @DefaultValue("") String currentModelPrompt) {

    /** {@code rate}: fraccion de auto-aprobados a revision ciega (0 a 1); {@code rateByTypology} la sobreescribe. */
    public record BlindSampling(@DefaultValue("0.02") double rate, @DefaultValue("") String seed,
                                @DefaultValue Map<String, Double> rateByTypology) {
    }

    /** Deriva: error silente maximo (fraccion), caida de STP en puntos, dias de linea base y muestra minima. */
    public record Drift(@DefaultValue("0.05") double silentErrorMax, @DefaultValue("10") double stpDropPoints,
                        @DefaultValue("7") int baselineDays, @DefaultValue("20") int minSamples) {
    }

    /** Muestra minima para publicar el p95 de latencia y costo. */
    public record Performance(@DefaultValue("30") int minSample) {
    }

    public record Calibration(@DefaultValue("0.98") double targetAuto, @DefaultValue("0.85") double targetRevisar,
                              @DefaultValue("30") int minFieldSamples, @DefaultValue("10") int eceBins) {
    }

    /** Directorio con los JSON de verdad terreno generados por tools/synthetic-oficios. */
    public record GoldenSet(@DefaultValue("") String directory) {
    }

    /** Directorio con las predicciones registradas por par modelo+prompt (adaptador por archivos). */
    public record Runner(@DefaultValue("") String predictionsDir) {
    }

    public record Scheduler(@DefaultValue("true") boolean enabled, @DefaultValue("0 15 1 * * *") String cron) {
    }
}
