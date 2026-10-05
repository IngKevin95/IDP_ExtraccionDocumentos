package com.idp.document.domain;

/** Roles revalidados contra role_assignment en cada request. */
public final class Role {
    public static final String OPERATOR = "DOCUMENT_OPERATOR";
    public static final String DATA_STEWARD = "DATA_STEWARD";
    public static final String ADMIN = "DOCUMENT_ADMIN";

    private Role() {
    }
}
