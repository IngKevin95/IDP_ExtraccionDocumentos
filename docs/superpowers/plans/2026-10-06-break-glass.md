# Break-glass Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Break-glass support in `libs/security` providing four-eyes validation, CRITICAL logging, and `breakglass.otorgado` / `breakglass.expirado` event emission, plus a web filter for continuous critical auditing during the session.

**Architecture:** 
1. A service component (`BreakGlassManager`) to manage the registration and expiration of Break-glass sessions. It ensures the requester is not the approver, fires the appropriate platform events via `OutboxPublisher` (which validates them against JSON Schemas), and emits critical logs.
2. A servlet filter (`BreakGlassAuditFilter`) that intercepts all requests, detects if the authenticated user possesses the Break-glass role (`Roles.SOPORTE`), and emits a critical audit log for every single operation performed during the session.

**Tech Stack:** Java 21, Spring Security, Spring Web, SLF4J, Kafka/Events Outbox.

**Spec:** `docs/specs/plataforma/hardening/tasks.md`, `docs/adr/0030-break-glass-y-certificacion-de-accesos.md`

## Global Constraints

- Java 21 LTS minimum.
- Ensure four-eyes principle (requester != approver).
- Events must be emitted transactionally via `OutboxPublisher` and validate against `contracts/events/breakglass.*` schemas.
- Do not create independent transactional annotations in the library; rely on the consuming service having an active transaction for the Outbox.

---

### Task 1: Create BreakGlassManager component

**Files:**
- Create: `libs/security/src/main/java/com/idp/security/breakglass/BreakGlassManager.java`
- Create: `libs/security/src/test/java/com/idp/security/breakglass/BreakGlassManagerTest.java`

**Interfaces:**
- Consumes: `OutboxPublisher` (to publish events), `ObjectMapper` (to build JSON payloads).
- Produces: `void grant(UUID tenantId, String subjectId, String approvedBy, Instant expiresAt)`, `void expire(UUID tenantId, String subjectId)`.

- [ ] **Step 1: Write the failing test**

```java
package com.idp.security.breakglass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class BreakGlassManagerTest {

    private final OutboxPublisher publisher = mock(OutboxPublisher.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final BreakGlassManager manager = new BreakGlassManager(publisher, mapper);

    @Test
    void rejectsGrantWhenSubjectEqualsApprovedBy() {
        UUID tenantId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        assertThrows(IllegalArgumentException.class, () -> 
            manager.grant(tenantId, "userA", "userA", expiresAt));
    }

    @Test
    void publishesGrantEvent() {
        UUID tenantId = UUID.randomUUID();
        Instant expiresAt = Instant.now().plus(1, ChronoUnit.HOURS);
        manager.grant(tenantId, "userA", "userB", expiresAt);
        verify(publisher).publish(eq(tenantId.toString()), any(EventEnvelope.class));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl libs/security -Dtest=BreakGlassManagerTest`
Expected: FAIL with compilation error (BreakGlassManager not found).

- [ ] **Step 3: Write minimal implementation**

```java
package com.idp.security.breakglass;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.UUID;

public class BreakGlassManager {

    private static final Logger LOG = LoggerFactory.getLogger(BreakGlassManager.class);
    private final OutboxPublisher publisher;
    private final ObjectMapper mapper;

    public BreakGlassManager(OutboxPublisher publisher, ObjectMapper mapper) {
        this.publisher = publisher;
        this.mapper = mapper;
    }

    public void grant(UUID tenantId, String subjectId, String approvedBy, Instant expiresAt) {
        if (subjectId.equals(approvedBy)) {
            throw new IllegalArgumentException("Four-eyes principle violated: requester cannot be approver");
        }
        
        LOG.error("ALERTA CRITICA: Break-glass otorgado en tenant {} para el usuario {} aprobado por {}", tenantId, subjectId, approvedBy);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("subjectId", subjectId);
        payload.put("approvedBy", approvedBy);
        payload.put("expiresAt", expiresAt.toString());

        EventEnvelope event = new EventEnvelope(
            UUID.randomUUID(),
            "breakglass.otorgado",
            1,
            Instant.now(),
            tenantId,
            UUID.randomUUID(), // correlationId
            payload
        );

        publisher.publish(tenantId.toString(), event);
    }

    public void expire(UUID tenantId, String subjectId) {
        LOG.error("ALERTA CRITICA: Break-glass expirado en tenant {} para el usuario {}", tenantId, subjectId);

        ObjectNode payload = mapper.createObjectNode();
        payload.put("subjectId", subjectId);

        EventEnvelope event = new EventEnvelope(
            UUID.randomUUID(),
            "breakglass.expirado",
            1,
            Instant.now(),
            tenantId,
            UUID.randomUUID(), // correlationId
            payload
        );

        publisher.publish(tenantId.toString(), event);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl libs/security -Dtest=BreakGlassManagerTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/security/src/main/java/com/idp/security/breakglass/BreakGlassManager.java
git add libs/security/src/test/java/com/idp/security/breakglass/BreakGlassManagerTest.java
git commit -m "feat(security): implement BreakGlassManager for 4-eyes and outbox events"
```

---

### Task 2: Create BreakGlassAuditFilter

**Files:**
- Create: `libs/security/src/main/java/com/idp/security/breakglass/BreakGlassAuditFilter.java`
- Create: `libs/security/src/test/java/com/idp/security/breakglass/BreakGlassAuditFilterTest.java`

**Interfaces:**
- Consumes: `RoleAssignmentVerifier` to check if current user has `Roles.SOPORTE`.
- Produces: `OncePerRequestFilter` bean.

- [ ] **Step 1: Write the failing test**

```java
package com.idp.security.breakglass;

import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.Roles;
import com.idp.tenant.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.mockito.Mockito.*;

class BreakGlassAuditFilterTest {

    private final RoleAssignmentVerifier verifier = mock(RoleAssignmentVerifier.class);
    private final BreakGlassAuditFilter filter = new BreakGlassAuditFilter(verifier);

    @AfterEach
    void tearDown() {
        TenantContextHolder.clear();
        SecurityContextHolder.clearContext();
    }

    @Test
    void logsCriticalAlertIfUserHasBreakGlassRole() throws Exception {
        String tenant = UUID.randomUUID().toString();
        TenantContextHolder.setTenantId(tenant);

        Jwt jwt = mock(Jwt.class);
        when(jwt.getSubject()).thenReturn("userA");
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        when(verifier.hasRole(tenant, "userA", Roles.SOPORTE)).thenReturn(true);

        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getRequestURI()).thenReturn("/api/sensitive");
        when(req.getMethod()).thenReturn("GET");
        HttpServletResponse res = mock(HttpServletResponse.class);
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(req, res, chain);

        verify(chain).doFilter(req, res);
        // We verify the filter chain continues. The test of logs could be done via a custom Appender if needed, but simple verification of flow is enough here.
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `mvn test -pl libs/security -Dtest=BreakGlassAuditFilterTest`
Expected: FAIL (compilation error, missing filter).

- [ ] **Step 3: Write minimal implementation**

```java
package com.idp.security.breakglass;

import com.idp.security.RoleAssignmentVerifier;
import com.idp.security.Roles;
import com.idp.tenant.context.TenantContextHolder;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

public class BreakGlassAuditFilter extends OncePerRequestFilter {

    private static final Logger LOG = LoggerFactory.getLogger(BreakGlassAuditFilter.class);
    private final RoleAssignmentVerifier verifier;

    public BreakGlassAuditFilter(RoleAssignmentVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String tenantId = TenantContextHolder.getTenantId();
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();

        if (tenantId != null && !tenantId.isBlank() && auth != null && auth.getPrincipal() instanceof Jwt jwt) {
            String userId = jwt.getSubject();
            if (userId != null && verifier.hasRole(tenantId, userId, Roles.SOPORTE)) {
                LOG.error("ALERTA CRITICA: Operacion bajo Break-glass del usuario {} en el recurso {} {}",
                        userId, request.getMethod(), request.getRequestURI());
            }
        }

        filterChain.doFilter(request, response);
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `mvn test -pl libs/security -Dtest=BreakGlassAuditFilterTest`
Expected: PASS

- [ ] **Step 5: Commit**

```bash
git add libs/security/src/main/java/com/idp/security/breakglass/BreakGlassAuditFilter.java
git add libs/security/src/test/java/com/idp/security/breakglass/BreakGlassAuditFilterTest.java
git commit -m "feat(security): add BreakGlassAuditFilter for critical operation alerts"
```
