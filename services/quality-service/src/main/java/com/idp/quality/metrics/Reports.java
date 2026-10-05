package com.idp.quality.metrics;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Respuestas de la API de reportes (nombres en snake_case segun contracts/openapi/quality-service.yaml). */
public final class Reports {

    private Reports() {
    }

    public record StpDay(LocalDate date, @JsonProperty("total_documents") int totalDocuments,
                         @JsonProperty("stp_count") int stpCount, @JsonProperty("hitl_count") int hitlCount,
                         @JsonProperty("stp_percentage") double stpPercentage,
                         @JsonProperty("hitl_percentage") double hitlPercentage) {
    }

    public record StpReport(@JsonProperty("tenant_id") UUID tenantId, List<StpDay> data) {
    }

    public record SilentErrorDay(LocalDate date, @JsonProperty("blind_samples_total") int blindSamplesTotal,
                                 @JsonProperty("silent_errors_found") int silentErrorsFound,
                                 @JsonProperty("silent_error_percentage") double silentErrorPercentage,
                                 @JsonProperty("drift_detected") boolean driftDetected) {
    }

    public record SilentErrorReport(@JsonProperty("tenant_id") UUID tenantId, List<SilentErrorDay> data) {
    }

    /** Correccion humana por campo; precision/recall vienen de la ultima evaluacion del golden set, si existe. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record FieldRow(String tipologia, String campo, int corrections,
                           @JsonProperty("blind_corrections") int blindCorrections,
                           @JsonProperty("correction_rate") Double correctionRate,
                           @JsonProperty("golden_precision") Double goldenPrecision,
                           @JsonProperty("golden_recall") Double goldenRecall) {
    }

    public record FieldReport(@JsonProperty("tenant_id") UUID tenantId, List<FieldRow> data) {
    }

    /** p95 solo se publica con la muestra minima (sufficient = true). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record PerformanceReport(@JsonProperty("tenant_id") UUID tenantId,
                                    @JsonProperty("latency_samples") int latencySamples,
                                    @JsonProperty("latency_p95_ms") Long latencyP95Ms,
                                    @JsonProperty("cost_samples") int costSamples,
                                    @JsonProperty("cost_p95_micros") Long costP95Micros,
                                    @JsonProperty("cost_avg_micros") Double costAvgMicros,
                                    @JsonProperty("min_sample") int minSample, boolean sufficient) {
    }
}
