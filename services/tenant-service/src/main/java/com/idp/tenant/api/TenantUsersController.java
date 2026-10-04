package com.idp.tenant.api;

import com.idp.tenant.api.ApiModels.RoleAssignmentResponse;
import com.idp.tenant.api.ApiModels.UserAssignRequest;
import com.idp.tenant.application.UserAccessService;
import com.idp.tenant.context.TenantContextHolder;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Usuarios y roles del tenant. El tenant es el del token (TenantContextHolder, fijado por el filtro de
 * revalidacion), nunca un parametro del path o body.
 */
@RestController
@RequestMapping("/v1/tenant/users")
public class TenantUsersController {
    private final UserAccessService users;

    public TenantUsersController(UserAccessService users) {
        this.users = users;
    }

    private static UUID tenant() {
        return UUID.fromString(TenantContextHolder.getTenantId());
    }

    @GetMapping
    public List<RoleAssignmentResponse> list() {
        return users.list(tenant()).stream().map(RoleAssignmentResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public RoleAssignmentResponse assign(@Valid @RequestBody UserAssignRequest req, Authentication auth) {
        return RoleAssignmentResponse.of(users.assign(tenant(), req.userId(), req.role(), req.expiresAt(), auth.getName()));
    }

    @DeleteMapping("/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void revoke(@PathVariable("userId") String userId, Authentication auth) {
        users.revoke(tenant(), userId, auth.getName());
    }
}
