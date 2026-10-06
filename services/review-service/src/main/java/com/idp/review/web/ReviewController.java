package com.idp.review.web;

import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.ReviewRepository.Scope;
import com.idp.review.service.Caller;
import com.idp.review.service.CallerResolver;
import com.idp.review.service.CropService;
import com.idp.review.service.CropService.CropLink;
import com.idp.review.service.Exceptions.InvalidRequestException;
import com.idp.review.service.ReviewQueryService;
import com.idp.review.service.ReviewTaskService;
import com.idp.review.service.ReviewTaskService.CorrectionInput;
import com.idp.review.web.ReviewDtos.CorrectionRequest;
import com.idp.review.web.ReviewDtos.CorrectionResponse;
import com.idp.review.web.ReviewDtos.CropLinkResponse;
import com.idp.review.web.ReviewDtos.FieldResponse;
import com.idp.review.web.ReviewDtos.MetricsResponse;
import com.idp.review.web.ReviewDtos.PageResponse;
import com.idp.review.web.ReviewDtos.QueueItemResponse;
import com.idp.review.web.ReviewDtos.ReassignRequest;
import com.idp.review.web.ReviewDtos.TaskResponse;
import com.idp.security.Roles;
import java.time.Clock;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API de revision (contracts/openapi/review-service.yaml). El tenant y el revisor salen siempre del JWT. */
@RestController
@RequestMapping("/v1/review")
public class ReviewController {

    private static final String[] READERS = {Roles.REVISOR, Roles.TENANT_ADMIN};

    private final CallerResolver callers;
    private final ReviewTaskService tasks;
    private final ReviewQueryService queries;
    private final CropService crops;
    private final Clock clock;

    public ReviewController(CallerResolver callers, ReviewTaskService tasks, ReviewQueryService queries,
                            CropService crops, Clock clock) {
        this.callers = callers;
        this.tasks = tasks;
        this.queries = queries;
        this.crops = crops;
        this.clock = clock;
    }

    // ---- consulta --------------------------------------------------------------------------------------------

    @GetMapping("/tasks")
    public PageResponse<TaskResponse> list(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "mine", defaultValue = "false") boolean mine,
            @RequestParam(value = "escalated", defaultValue = "false") boolean escalated,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Caller caller = callers.require(jwt, READERS);
        TaskStatus s = status == null ? null : parse(TaskStatus.class, status, "status");
        return PageResponse.of(queries.list(caller, s, mine, escalated, page, size), t -> TaskResponse.of(t, clock, showBlind(caller)));
    }

    @GetMapping("/tasks/{taskId}")
    public TaskResponse get(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId) {
        Caller caller = callers.require(jwt, READERS);
        return TaskResponse.of(queries.get(caller, taskId), clock, showBlind(caller));
    }

    @GetMapping("/tasks/{taskId}/fields")
    public List<FieldResponse> fields(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId) {
        return queries.fields(callers.require(jwt, READERS), taskId).stream().map(FieldResponse::of).toList();
    }

    /** Cola por campo: cada elemento es un campo dudoso pendiente. {@code scope}: ALL, MINE o UNASSIGNED. */
    @GetMapping("/queue")
    public PageResponse<QueueItemResponse> queue(@AuthenticationPrincipal Jwt jwt,
            @RequestParam(value = "scope", defaultValue = "ALL") String scope,
            @RequestParam(value = "page", defaultValue = "0") int page,
            @RequestParam(value = "size", defaultValue = "20") int size) {
        Caller caller = callers.require(jwt, READERS);
        return PageResponse.of(queries.queue(caller, parse(Scope.class, scope, "scope"), page, size),
                QueueItemResponse::of);
    }

    @GetMapping("/metrics")
    public MetricsResponse metrics(@AuthenticationPrincipal Jwt jwt) {
        return MetricsResponse.of(queries.metrics(callers.require(jwt, READERS)));
    }

    // ---- asignacion ------------------------------------------------------------------------------------------

    @PostMapping("/tasks/{taskId}/claim")
    public TaskResponse claim(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId) {
        Caller caller = callers.require(jwt, Roles.REVISOR);
        return TaskResponse.of(tasks.claim(caller, taskId), clock, showBlind(caller));
    }

    @PostMapping("/tasks/{taskId}/release")
    public TaskResponse release(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId) {
        Caller caller = callers.require(jwt, Roles.REVISOR);
        return TaskResponse.of(tasks.release(caller, taskId), clock, showBlind(caller));
    }

    @PostMapping("/tasks/{taskId}/reassign")
    public TaskResponse reassign(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId,
            @RequestBody ReassignRequest body) {
        Caller caller = callers.require(jwt, Roles.TENANT_ADMIN);
        return TaskResponse.of(tasks.reassign(caller, taskId, body == null ? null : body.assigneeId()), clock,
                showBlind(caller));
    }

    // ---- correcciones ----------------------------------------------------------------------------------------

    @GetMapping("/tasks/{taskId}/corrections")
    public List<CorrectionResponse> corrections(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("taskId") UUID taskId) {
        return queries.corrections(callers.require(jwt, READERS), taskId).stream().map(CorrectionResponse::of)
                .toList();
    }

    @PostMapping("/tasks/{taskId}/corrections")
    public List<CorrectionResponse> addCorrections(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("taskId") UUID taskId, @RequestBody List<CorrectionRequest> body) {
        Caller caller = callers.require(jwt, Roles.REVISOR);
        if (body == null) {
            throw new InvalidRequestException("Cuerpo requerido");
        }
        List<CorrectionInput> inputs = body.stream()
                .map(c -> c == null ? null : new CorrectionInput(c.fieldName(), c.originalValue(), c.correctedValue()))
                .toList();
        return tasks.addCorrections(caller, taskId, inputs).stream().map(CorrectionResponse::of).toList();
    }

    // ---- aprobacion y rechazo --------------------------------------------------------------------------------

    @PostMapping("/tasks/{taskId}/approve")
    public TaskResponse approve(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId) {
        Caller caller = callers.require(jwt, Roles.REVISOR);
        return TaskResponse.of(tasks.approve(caller, taskId), clock, showBlind(caller));
    }

    @PostMapping("/tasks/{taskId}/approve-secondary")
    public TaskResponse approveSecondary(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId) {
        Caller caller = callers.require(jwt, Roles.REVISOR);
        return TaskResponse.of(tasks.approveSecondary(caller, taskId), clock, showBlind(caller));
    }

    @PostMapping("/tasks/{taskId}/reject")
    public TaskResponse reject(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId) {
        Caller caller = callers.require(jwt, Roles.REVISOR);
        return TaskResponse.of(tasks.reject(caller, taskId), clock, showBlind(caller));
    }

    // ---- recorte de pagina -----------------------------------------------------------------------------------

    @GetMapping("/tasks/{taskId}/fields/{fieldId}/crop-link")
    public CropLinkResponse cropLink(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId,
            @PathVariable("fieldId") UUID fieldId) {
        CropLink link = crops.link(callers.require(jwt, Roles.REVISOR), taskId, fieldId);
        return new CropLinkResponse(link.url(), link.expiresAt());
    }

    /** Destino del enlace firmado de crop-link: el recorte PNG del campo (el binario va cifrado en el bucket). */
    @GetMapping("/tasks/{taskId}/fields/{fieldId}/crop")
    public ResponseEntity<byte[]> crop(@AuthenticationPrincipal Jwt jwt, @PathVariable("taskId") UUID taskId,
            @PathVariable("fieldId") UUID fieldId, @RequestParam("exp") long exp, @RequestParam("sig") String sig) {
        byte[] png = crops.crop(callers.require(jwt, Roles.REVISOR), taskId, fieldId, exp, sig);
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Disposition", "inline")
                .body(png);
    }

    /** blindSample solo se devuelve a roles de gestion (TENANT_ADMIN): el REVISOR no debe saber que la tarea es ciega. */
    private static boolean showBlind(Caller caller) {
        return caller.has(Roles.TENANT_ADMIN);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String value, String field) {
        try {
            return Enum.valueOf(type, value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("Valor invalido para " + field);
        }
    }
}
