package com.idp.quality.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.idp.quality.api.CallerResolver.Caller;
import com.idp.quality.config.QualityProperties;
import com.idp.quality.golden.EvaluationResult.ThresholdRow;
import com.idp.quality.golden.GoldenRepository;
import com.idp.quality.metrics.MetricsRepository;
import com.idp.security.Roles;
import java.util.List;
import java.util.Map;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Consulta de umbrales calibrados por par modelo+prompt y de la cola de muestreo ciego. Los umbrales
 * {@code attainable=false} significan "nunca auto-aprobar": el consumidor debe respetarlo.
 */
@RestController
@RequestMapping("/v1/quality")
public class CalibrationController {

    public record ThresholdView(String tipologia, String campo, String scope,
                                @JsonProperty("tau_auto") double tauAuto,
                                @JsonProperty("tau_revisar") double tauRevisar, boolean attainable, int samples,
                                @JsonProperty("target_precision") double targetPrecision) {
    }

    public record Thresholds(@JsonProperty("model_prompt_key") String modelPromptKey, List<ThresholdView> data) {
    }

    /** Solo agregados (SEC-051): ningun identificador de documento ni de muestra sale hacia el Data Steward. */
    public record PendingBlind(int pending, int reviewed,
                               @JsonProperty("pending_by_age") Map<String, Integer> pendingByAge) {
    }

    private final GoldenRepository golden;
    private final MetricsRepository metrics;
    private final CallerResolver callers;
    private final QualityProperties props;

    public CalibrationController(GoldenRepository golden, MetricsRepository metrics, CallerResolver callers,
                                 QualityProperties props) {
        this.golden = golden;
        this.metrics = metrics;
        this.callers = callers;
        this.props = props;
    }

    @GetMapping("/calibration/thresholds")
    public Thresholds thresholds(@AuthenticationPrincipal Jwt jwt,
                                 @RequestParam(value = "model_prompt_key", required = false) String key,
                                 @RequestParam(value = "tipologia", required = false) String tipologia) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD, Roles.RIESGO_MODELO);
        String k = key != null ? key : props.currentModelPrompt();
        if (!k.matches("[A-Za-z0-9._:/-]{1,120}") || k.contains("..")) {
            throw new IllegalArgumentException("model_prompt_key invalido");
        }
        List<ThresholdView> rows = golden.thresholds(c.tenantId(), k, ReportsController.tipologia(tipologia))
            .stream().map(CalibrationController::view).toList();
        return new Thresholds(k, rows);
    }

    /** Cola de muestreo ciego: conteos y antiguedad, nunca los oficios elegidos (SEC-051). */
    @GetMapping("/blind-samples/pending")
    public PendingBlind pendingBlind(@AuthenticationPrincipal Jwt jwt) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        MetricsRepository.BlindSummary s = metrics.blindSummary(c.tenantId(), java.time.Instant.now());
        return new PendingBlind(s.pending(), s.reviewed(), Map.of("lt_1d", s.pendingUnderDay(),
            "1d_7d", s.pendingUnderWeek(), "gt_7d", s.pendingOverWeek()));
    }

    private static ThresholdView view(ThresholdRow r) {
        return new ThresholdView(r.tipologia(), r.campo(), r.scope(), r.tauAuto(), r.tauRevisar(), r.attainable(),
            r.samples(), r.targetPrecision());
    }
}
