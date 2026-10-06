package com.idp.quality.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.quality.api.CallerResolver.Caller;
import com.idp.quality.api.GlobalExceptionHandler.NotFoundException;
import com.idp.quality.config.QualityProperties;
import com.idp.quality.golden.EvaluationService;
import com.idp.quality.golden.GoldenDocument;
import com.idp.quality.golden.GoldenRepository;
import com.idp.quality.golden.GoldenRepository.EvaluationRow;
import com.idp.quality.golden.GoldenSetLoader;
import com.idp.security.Roles;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Administracion del golden set sintetico y disparo asincrono de evaluacion y replay. Exclusivo del Data Steward
 * (SEC-005). Las rutas viven en {@code /v1/quality/reports/golden-set} (contrato OpenAPI) y en el alias
 * {@code /v1/quality/golden-set} (spec del servicio).
 */
@RestController
@RequestMapping({"/v1/quality/reports/golden-set", "/v1/quality/golden-set"})
public class GoldenSetController {

    private static final Logger LOG = LoggerFactory.getLogger(GoldenSetController.class);
    private static final String KEY = "[A-Za-z0-9._:/-]{1,120}";
    private static final int MAX_FIELDS = 64;
    private static final int MAX_VALUE = 2000;

    public record DocumentView(UUID id, String nombre, String tipologia, List<String> tags,
                               @JsonProperty("payload_sintetico_json") Map<String, String> payload) {
    }

    public record EvaluateRequest(@JsonProperty("model_prompt_key") String modelPromptKey) {
    }

    public record ReplayRequest(@JsonProperty("baseline_model_prompt_key") String baseline,
                                @JsonProperty("candidate_model_prompt_key") String candidate) {
    }

    public record JobResponse(@JsonProperty("job_id") UUID jobId) {
    }

    public record ImportResponse(int imported) {
    }

    public record JobStatus(@JsonProperty("job_id") UUID jobId, String kind, String status,
                            @JsonProperty("model_prompt_key") String modelPromptKey, Double accuracy,
                            Double precision, Double recall, Double f1,
                            @JsonProperty("ece_raw") Double eceRaw,
                            @JsonProperty("ece_calibrated") Double eceCalibrated, Integer samples, String error,
                            Object result) {
    }

    private final GoldenRepository repo;
    private final EvaluationService evaluations;
    private final CallerResolver callers;
    private final QualityProperties props;
    private final ObjectMapper mapper;

    public GoldenSetController(GoldenRepository repo, EvaluationService evaluations, CallerResolver callers,
                               QualityProperties props, ObjectMapper idpObjectMapper) {
        this.repo = repo;
        this.evaluations = evaluations;
        this.callers = callers;
        this.props = props;
        this.mapper = idpObjectMapper;
    }

    @GetMapping
    public List<DocumentView> list(@AuthenticationPrincipal Jwt jwt) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        return repo.list(c.tenantId()).stream().map(GoldenSetController::view).toList();
    }

    @GetMapping("/{id}")
    public DocumentView get(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        return repo.find(c.tenantId(), id).map(GoldenSetController::view)
            .orElseThrow(() -> new NotFoundException("golden"));
    }

    /**
     * Crea un oficio sintetico. El cuerpo se lee crudo para conservar los decimales exactos (BigDecimal): un monto
     * no debe pasar por double.
     */
    @PostMapping
    public ResponseEntity<DocumentView> create(@AuthenticationPrincipal Jwt jwt, @RequestBody String raw)
            throws JsonProcessingException {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        JsonNode in = mapper.readTree(raw);
        if (!in.isObject()) {
            throw new IllegalArgumentException("Documento invalido");
        }
        String nombre = in.path("nombre").isTextual() ? in.get("nombre").asText() : "";
        if (nombre.isBlank() || nombre.length() > 255) {
            throw new IllegalArgumentException("Documento invalido");
        }
        // Misma validacion que el import por archivo: exige "sintetico": true (RN-07). El id es opcional en la API.
        com.fasterxml.jackson.databind.node.ObjectNode candidate = ((com.fasterxml.jackson.databind.node.ObjectNode)
            in).deepCopy();
        if (!candidate.hasNonNull("id")) {
            candidate.put("id", "api-" + UUID.randomUUID());
        }
        GoldenSetLoader.Loaded loaded = new GoldenSetLoader(mapper).parse(candidate, "POST golden-set");
        String tipologia = loaded.tipologia();
        if (!ReportsController.TIPOLOGIAS.contains(tipologia)) {
            throw new IllegalArgumentException("Documento invalido");
        }
        Map<String, String> truth = loaded.verdad();
        if (truth.size() > MAX_FIELDS || truth.values().stream().anyMatch(v -> v.length() > MAX_VALUE)) {
            throw new IllegalArgumentException("Payload fuera de limites");
        }
        List<String> tags = loaded.tags();
        if (tags.stream().anyMatch(t -> !t.matches("[A-Za-z0-9_.-]{1,40}"))) {
            throw new IllegalArgumentException("Tag invalido");
        }
        UUID id = repo.save(c.tenantId(), null, nombre, tipologia, tags, truth, "API");
        LOG.info("Oficio sintetico creado por API tenant={} id={}", c.tenantId(), id);
        return ResponseEntity.status(HttpStatus.CREATED).body(new DocumentView(id, nombre, tipologia, tags, truth));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        if (!repo.delete(c.tenantId(), id)) {
            throw new NotFoundException("golden");
        }
        return ResponseEntity.noContent().build();
    }

    /** Importa los JSON de verdad terreno del directorio configurado (nunca una ruta del cliente). */
    @PostMapping("/import")
    public ImportResponse importDirectory(@AuthenticationPrincipal Jwt jwt) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        String dir = props.goldenSet().directory();
        if (dir.isBlank()) {
            throw new IllegalArgumentException("quality.golden-set.directory no configurado");
        }
        try {
            List<GoldenSetLoader.Loaded> loaded = new GoldenSetLoader(mapper).loadDirectory(Path.of(dir));
            for (GoldenSetLoader.Loaded l : loaded) {
                repo.save(c.tenantId(), l.externalId(), l.nombre(), l.tipologia(), l.tags(), l.verdad(), "IMPORT");
            }
            return new ImportResponse(loaded.size());
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Importacion del golden set rechazada: {}", e.getMessage());
            throw new IllegalArgumentException("Importacion rechazada", e);
        }
    }

    /** AC-04: encola la evaluacion del par modelo+prompt y responde 202 de inmediato. */
    @PostMapping("/evaluate")
    public ResponseEntity<JobResponse> evaluate(@AuthenticationPrincipal Jwt jwt,
                                                @RequestBody(required = false) EvaluateRequest req) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        String key = req != null && req.modelPromptKey() != null ? req.modelPromptKey()
            : props.currentModelPrompt();
        key(key);
        return ResponseEntity.accepted().body(new JobResponse(evaluations.submitEvaluation(c.tenantId(), key)));
    }

    @PostMapping("/replay")
    public ResponseEntity<JobResponse> replay(@AuthenticationPrincipal Jwt jwt, @RequestBody ReplayRequest req) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        String baseline = req.baseline() != null ? req.baseline() : props.currentModelPrompt();
        key(baseline);
        key(req.candidate());
        return ResponseEntity.accepted()
            .body(new JobResponse(evaluations.submitReplay(c.tenantId(), baseline, req.candidate())));
    }

    @GetMapping("/evaluations/{id}")
    public JobStatus evaluation(@AuthenticationPrincipal Jwt jwt, @PathVariable("id") UUID id) {
        Caller c = callers.require(jwt, Roles.DATA_STEWARD);
        EvaluationRow r = repo.findEvaluation(c.tenantId(), id).orElseThrow(() -> new NotFoundException("job"));
        Object result = repo.resultJson(c.tenantId(), id).map(this::parse).orElse(null);
        return new JobStatus(r.id(), r.kind(), r.status(), r.versionPrompt(), r.accuracy(), r.precision(),
            r.recall(), r.f1(), r.eceRaw(), r.eceCalibrated(), r.samples(), r.error(), result);
    }

    private Object parse(String json) {
        try {
            return mapper.readValue(json, Object.class);
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static void key(String key) {
        if (key == null || !key.matches(KEY) || key.contains("..")) {
            throw new IllegalArgumentException("model_prompt_key invalido");
        }
    }

    private static DocumentView view(GoldenDocument d) {
        return new DocumentView(d.id(), d.nombre(), d.tipologia(), d.tags(), d.verdad());
    }
}
