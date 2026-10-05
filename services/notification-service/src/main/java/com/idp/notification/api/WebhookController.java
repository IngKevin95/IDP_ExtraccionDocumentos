package com.idp.notification.api;

import com.idp.notification.api.WebhookDtos.CreateRequest;
import com.idp.notification.api.WebhookDtos.CreatedResponse;
import com.idp.notification.api.WebhookDtos.DeliveryItem;
import com.idp.notification.api.WebhookDtos.DeliveryPageResponse;
import com.idp.notification.api.WebhookDtos.ListItem;
import com.idp.notification.api.WebhookDtos.PolicyBody;
import com.idp.notification.api.WebhookDtos.RotatedResponse;
import com.idp.notification.domain.DeliveryStatus;
import com.idp.notification.service.Caller;
import com.idp.notification.service.CallerResolver;
import com.idp.notification.service.Exceptions.InvalidRequestException;
import com.idp.notification.service.WebhookAdminService;
import com.idp.notification.service.WebhookAdminService.DeliveryPage;
import com.idp.security.Roles;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API de webhooks (contracts/openapi/notification-service.yaml). El tenant sale siempre del JWT. */
@RestController
@RequestMapping("/v1/webhooks")
public class WebhookController {

    private static final String[] READERS = {Roles.TENANT_ADMIN, Roles.OPERADOR};

    private final CallerResolver callers;
    private final WebhookAdminService service;
    private final Clock clock;

    public WebhookController(CallerResolver callers, WebhookAdminService service, Clock clock) {
        this.callers = callers;
        this.service = service;
        this.clock = clock;
    }

    @PostMapping
    public ResponseEntity<CreatedResponse> create(@AuthenticationPrincipal Jwt jwt, @RequestBody CreateRequest body) {
        Caller caller = callers.require(jwt, Roles.TENANT_ADMIN);
        return ResponseEntity.status(HttpStatus.CREATED)
            .header("Cache-Control", "no-store")
            .body(CreatedResponse.of(service.create(caller, body.url(), body.events())));
    }

    @GetMapping
    public List<ListItem> list(@AuthenticationPrincipal Jwt jwt) {
        Caller caller = callers.require(jwt, READERS);
        return service.list(caller).stream().map(s -> ListItem.of(s, clock.instant())).toList();
    }

    @DeleteMapping("/{webhookId}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Jwt jwt, @PathVariable("webhookId") UUID webhookId) {
        service.delete(callers.require(jwt, Roles.TENANT_ADMIN), webhookId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{webhookId}/secret/rotate")
    public ResponseEntity<RotatedResponse> rotate(@AuthenticationPrincipal Jwt jwt,
                                                  @PathVariable("webhookId") UUID webhookId) {
        Caller caller = callers.require(jwt, Roles.TENANT_ADMIN);
        return ResponseEntity.ok().header("Cache-Control", "no-store")
            .body(RotatedResponse.of(service.rotate(caller, webhookId)));
    }

    @DeleteMapping("/{webhookId}/secret/previous")
    public ResponseEntity<Void> endRotation(@AuthenticationPrincipal Jwt jwt,
                                            @PathVariable("webhookId") UUID webhookId) {
        service.endRotation(callers.require(jwt, Roles.TENANT_ADMIN), webhookId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/policy")
    public PolicyBody policy(@AuthenticationPrincipal Jwt jwt) {
        return PolicyBody.of(service.policy(callers.require(jwt, Roles.TENANT_ADMIN)));
    }

    @PutMapping("/policy")
    public PolicyBody updatePolicy(@AuthenticationPrincipal Jwt jwt, @RequestBody PolicyBody body) {
        Caller caller = callers.require(jwt, Roles.TENANT_ADMIN);
        return PolicyBody.of(service.updatePolicy(caller, body.allowedHosts(), body.maxAttempts(),
            Duration.ofSeconds(body.initialBackoffSeconds()), body.backoffMultiplier(),
            Duration.ofSeconds(body.maxBackoffSeconds())));
    }

    @GetMapping("/{webhookId}/deliveries")
    public DeliveryPageResponse deliveries(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("webhookId") UUID webhookId,
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "limit", defaultValue = "20") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset) {
        Caller caller = callers.require(jwt, READERS);
        DeliveryPage page = service.deliveries(caller, webhookId, parseStatus(status), limit, offset);
        return new DeliveryPageResponse(page.data().stream().map(DeliveryItem::of).toList(), page.limit(),
            page.offset(), page.total());
    }

    @PostMapping("/{webhookId}/deliveries/{deliveryId}/retry")
    public ResponseEntity<DeliveryItem> retry(@AuthenticationPrincipal Jwt jwt,
            @PathVariable("webhookId") UUID webhookId, @PathVariable("deliveryId") UUID deliveryId) {
        Caller caller = callers.require(jwt, Roles.TENANT_ADMIN);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(DeliveryItem.of(service.retry(caller, webhookId,
            deliveryId)));
    }

    private static DeliveryStatus parseStatus(String status) {
        if (status == null) {
            return null;
        }
        try {
            return DeliveryStatus.valueOf(status);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException("WEBHOOK_INVALID_STATUS", "Estado de entrega no valido");
        }
    }
}
