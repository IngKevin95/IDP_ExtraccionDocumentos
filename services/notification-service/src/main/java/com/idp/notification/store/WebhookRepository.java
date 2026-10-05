package com.idp.notification.store;

import com.idp.notification.domain.DeliveryStatus;
import com.idp.notification.domain.TenantPolicy;
import com.idp.notification.domain.WebhookDelivery;
import com.idp.notification.domain.WebhookEvent;
import com.idp.notification.domain.WebhookSubscription;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Acceso JDBC al silo del tenant (el DataSource enruta por el tenant del contexto). Todas las consultas filtran
 * ademas por tenant_id: defensa en profundidad si dos silos compartieran esquema.
 */
public class WebhookRepository {

    private static final String SUB_COLS = "id, tenant_id, url, events, active, secret_current, secret_previous, "
        + "secret_previous_expires_at, created_by, created_at, rotated_at";
    private static final String DEL_COLS = "id, tenant_id, webhook_id, document_id, source_event_id, correlation_id, "
        + "event_type, payload, status, attempts, next_attempt_at, last_attempt_at, delivered_at, last_http_status, "
        + "error_code, error_msg, created_at";

    private final JdbcTemplate jdbc;

    public WebhookRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ---- suscripciones ----

    public void insertSubscription(WebhookSubscription s) {
        jdbc.update("insert into webhook_subscription (" + SUB_COLS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            s.id(), s.tenantId(), s.url(), eventsCsv(s.events()), s.active(), s.secretCurrent(), s.secretPrevious(),
            ts(s.secretPreviousExpiresAt()), s.createdBy(), ts(s.createdAt()), ts(s.rotatedAt()));
    }

    public Optional<WebhookSubscription> findSubscription(UUID tenantId, UUID id) {
        return jdbc.query("select " + SUB_COLS + " from webhook_subscription where tenant_id = ? and id = ?",
            SUBSCRIPTION, tenantId, id).stream().findFirst();
    }

    public List<WebhookSubscription> activeSubscriptions(UUID tenantId) {
        return jdbc.query("select " + SUB_COLS + " from webhook_subscription where tenant_id = ? and active = true "
            + "order by created_at, id", SUBSCRIPTION, tenantId);
    }

    public int countActiveSubscriptions(UUID tenantId) {
        Integer n = jdbc.queryForObject("select count(*) from webhook_subscription where tenant_id = ? and active = true",
            Integer.class, tenantId);
        return n == null ? 0 : n;
    }

    public boolean deactivate(UUID tenantId, UUID id) {
        return jdbc.update("update webhook_subscription set active = false where tenant_id = ? and id = ? "
            + "and active = true", tenantId, id) == 1;
    }

    public void saveRotation(UUID tenantId, UUID id, String current, String previous, Instant previousExpiresAt,
                             Instant rotatedAt) {
        jdbc.update("update webhook_subscription set secret_current = ?, secret_previous = ?, "
            + "secret_previous_expires_at = ?, rotated_at = ? where tenant_id = ? and id = ?",
            current, previous, ts(previousExpiresAt), ts(rotatedAt), tenantId, id);
    }

    // ---- politica del tenant ----

    public Optional<TenantPolicy> findPolicy(UUID tenantId) {
        return jdbc.query("select allowed_hosts, max_attempts, initial_backoff_ms, backoff_multiplier, max_backoff_ms "
            + "from webhook_tenant_policy where tenant_id = ?", (rs, i) -> new TenantPolicy(
                hosts(rs.getString("allowed_hosts")), rs.getInt("max_attempts"),
                Duration.ofMillis(rs.getLong("initial_backoff_ms")), rs.getBigDecimal("backoff_multiplier").doubleValue(),
                Duration.ofMillis(rs.getLong("max_backoff_ms"))), tenantId).stream().findFirst();
    }

    public void savePolicy(UUID tenantId, TenantPolicy p, Instant now) {
        String hosts = String.join(",", p.allowedHosts());
        int updated = jdbc.update("update webhook_tenant_policy set allowed_hosts = ?, max_attempts = ?, "
            + "initial_backoff_ms = ?, backoff_multiplier = ?, max_backoff_ms = ?, updated_at = ? where tenant_id = ?",
            hosts, p.maxAttempts(), p.initialBackoff().toMillis(), p.backoffMultiplier(), p.maxBackoff().toMillis(),
            ts(now), tenantId);
        if (updated == 0) {
            jdbc.update("insert into webhook_tenant_policy (tenant_id, allowed_hosts, max_attempts, initial_backoff_ms, "
                + "backoff_multiplier, max_backoff_ms, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
                tenantId, hosts, p.maxAttempts(), p.initialBackoff().toMillis(), p.backoffMultiplier(),
                p.maxBackoff().toMillis(), ts(now));
        }
    }

    // ---- entregas ----

    /** Inserta la entrega; false si ya existia para (webhook, evento origen): idempotencia. */
    public boolean insertDelivery(WebhookDelivery d) {
        Integer exists = jdbc.queryForObject("select count(*) from webhook_delivery where webhook_id = ? "
            + "and source_event_id = ?", Integer.class, d.webhookId(), d.sourceEventId());
        if (exists != null && exists > 0) {
            return false;
        }
        jdbc.update("insert into webhook_delivery (" + DEL_COLS + ") values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, "
            + "?, ?, ?)", d.id(), d.tenantId(), d.webhookId(), d.documentId(), d.sourceEventId(), d.correlationId(),
            d.eventType(), d.payload(), d.status().name(), d.attempts(), ts(d.nextAttemptAt()), ts(d.lastAttemptAt()),
            ts(d.deliveredAt()), d.lastHttpStatus(), d.errorCode(), d.errorMsg(), ts(d.createdAt()));
        return true;
    }

    public Optional<WebhookDelivery> findDelivery(UUID tenantId, UUID id) {
        return jdbc.query("select " + DEL_COLS + " from webhook_delivery where tenant_id = ? and id = ?", DELIVERY,
            tenantId, id).stream().findFirst();
    }

    /**
     * Reclama hasta {@code limit} entregas vencidas con {@code FOR UPDATE SKIP LOCKED} (varias instancias no se
     * pisan) y las arrienda: next_attempt_at pasa a {@code now + lease}. Si el proceso muere, la entrega vuelve a
     * quedar disponible al vencer el arrendamiento. Debe ejecutarse en una transaccion corta.
     */
    public List<WebhookDelivery> claimDue(UUID tenantId, Instant now, Duration lease, int limit) {
        List<WebhookDelivery> due = jdbc.query("select " + DEL_COLS + " from webhook_delivery where tenant_id = ? "
            + "and status = 'PENDIENTE' and next_attempt_at <= ? order by next_attempt_at, id limit ? "
            + "for update skip locked", DELIVERY, tenantId, ts(now), limit);
        for (WebhookDelivery d : due) {
            jdbc.update("update webhook_delivery set next_attempt_at = ? where id = ?", ts(now.plus(lease)), d.id());
        }
        return due;
    }

    public void saveAttempt(UUID id, DeliveryStatus status, int attempts, Instant nextAttemptAt, Instant attemptedAt,
                            Instant deliveredAt, Integer httpStatus, String errorCode, String errorMsg) {
        jdbc.update("update webhook_delivery set status = ?, attempts = ?, next_attempt_at = ?, last_attempt_at = ?, "
            + "delivered_at = ?, last_http_status = ?, error_code = ?, error_msg = ? where id = ?",
            status.name(), attempts, ts(nextAttemptAt), ts(attemptedAt), ts(deliveredAt), httpStatus, errorCode,
            truncate(errorMsg, 200), id);
    }

    /** Reintento manual: solo desde FALLIDO (DLT); vuelve a PENDIENTE con el contador a cero. */
    public boolean requeueFailed(UUID tenantId, UUID id, Instant now) {
        return jdbc.update("update webhook_delivery set status = 'PENDIENTE', attempts = 0, next_attempt_at = ?, "
            + "error_code = null, error_msg = null where tenant_id = ? and id = ? and status = 'FALLIDO'",
            ts(now), tenantId, id) == 1;
    }

    public List<WebhookDelivery> listDeliveries(UUID tenantId, UUID webhookId, DeliveryStatus status, int limit,
                                                int offset) {
        List<Object> args = new ArrayList<>(List.of(tenantId, webhookId));
        String where = " where tenant_id = ? and webhook_id = ?";
        if (status != null) {
            where += " and status = ?";
            args.add(status.name());
        }
        args.add(limit);
        args.add(offset);
        return jdbc.query("select " + DEL_COLS + " from webhook_delivery" + where
            + " order by created_at desc, id limit ? offset ?", DELIVERY, args.toArray());
    }

    public int countDeliveries(UUID tenantId, UUID webhookId, DeliveryStatus status) {
        Integer n = status == null
            ? jdbc.queryForObject("select count(*) from webhook_delivery where tenant_id = ? and webhook_id = ?",
                Integer.class, tenantId, webhookId)
            : jdbc.queryForObject("select count(*) from webhook_delivery where tenant_id = ? and webhook_id = ? "
                + "and status = ?", Integer.class, tenantId, webhookId, status.name());
        return n == null ? 0 : n;
    }

    // ---- mapeo ----

    private static final RowMapper<WebhookSubscription> SUBSCRIPTION = (rs, i) -> new WebhookSubscription(
        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getString("url"),
        parseEvents(rs.getString("events")), rs.getBoolean("active"), rs.getString("secret_current"),
        rs.getString("secret_previous"), instant(rs, "secret_previous_expires_at"), rs.getString("created_by"),
        instant(rs, "created_at"), instant(rs, "rotated_at"));

    private static final RowMapper<WebhookDelivery> DELIVERY = (rs, i) -> new WebhookDelivery(
        rs.getObject("id", UUID.class), rs.getObject("tenant_id", UUID.class), rs.getObject("webhook_id", UUID.class),
        rs.getObject("document_id", UUID.class), rs.getObject("source_event_id", UUID.class),
        rs.getObject("correlation_id", UUID.class), rs.getString("event_type"), rs.getString("payload"),
        DeliveryStatus.valueOf(rs.getString("status")), rs.getInt("attempts"), instant(rs, "next_attempt_at"),
        instant(rs, "last_attempt_at"), instant(rs, "delivered_at"), (Integer) rs.getObject("last_http_status"),
        rs.getString("error_code"), rs.getString("error_msg"), instant(rs, "created_at"));

    private static Instant instant(ResultSet rs, String col) throws SQLException {
        OffsetDateTime v = rs.getObject(col, OffsetDateTime.class);
        return v == null ? null : v.toInstant();
    }

    private static OffsetDateTime ts(Instant i) {
        return i == null ? null : i.atOffset(ZoneOffset.UTC);
    }

    private static String eventsCsv(Set<WebhookEvent> events) {
        return events.stream().map(WebhookEvent::wireName).sorted().collect(Collectors.joining(","));
    }

    private static Set<WebhookEvent> parseEvents(String csv) {
        Set<WebhookEvent> out = EnumSet.noneOf(WebhookEvent.class);
        for (String name : csv.split(",")) {
            WebhookEvent.fromWire(name.strip()).ifPresent(out::add);
        }
        return out;
    }

    private static List<String> hosts(String csv) {
        return csv == null || csv.isBlank() ? List.of()
            : Arrays.stream(csv.split(",")).map(String::strip).filter(h -> !h.isEmpty()).toList();
    }

    private static String truncate(String s, int max) {
        return s == null ? null : s.substring(0, Math.min(s.length(), max));
    }
}
