package com.idp.review.service;

import java.util.Set;

/** Identidad revalidada del request: tenant y usuario del JWT y roles vigentes en role_assignment. */
public record Caller(String tenantId, String userId, Set<String> roles) {

    public boolean has(String role) {
        return roles.contains(role);
    }
}
