package com.idp.document.service;

import com.idp.document.domain.Classification;
import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.Role;
import java.util.Set;

/** Identidad revalidada del request: tenant y usuario del JWT y roles vigentes en role_assignment. */
public record Caller(String tenantId, String userId, Set<String> roles) {

    public boolean has(String role) {
        return roles.contains(role);
    }

    /** Ve documentos Altamente Confidenciales de otros cargadores. */
    public boolean privileged() {
        return has(Role.DATA_STEWARD) || has(Role.ADMIN);
    }

    /** Autorizacion por documento (SEC-018). */
    public boolean canView(DocumentRecord d) {
        return d.classification() != Classification.ALTAMENTE_CONFIDENCIAL || privileged()
                || userId.equals(d.uploadedBy());
    }
}
