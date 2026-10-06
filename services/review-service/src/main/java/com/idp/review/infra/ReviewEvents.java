package com.idp.review.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.idp.review.domain.Correction;
import com.idp.review.domain.ReviewTask;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * Publica por outbox el evento revision.completada (claim-check, sin PII: solo UUIDs y subjects). Debe invocarse
 * dentro de la transaccion que cambia el estado de la tarea. La particion es el documentId.
 */
@Component
public class ReviewEvents {

    public static final String APROBADO = "APROBADO";
    public static final String RECHAZADO = "RECHAZADO";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final OutboxPublisher outbox;

    public ReviewEvents(OutboxPublisher outbox) {
        this.outbox = outbox;
    }

    /**
     * @param secondReviewerId segundo aprobador, solo con criticalCorrection (cuatro ojos); null en otro caso
     */
    public void completada(ReviewTask task, String action, String reviewerId, String secondReviewerId,
                           boolean criticalCorrection) {
        completada(task, action, reviewerId, secondReviewerId, criticalCorrection, null, false, List.of());
    }

    /**
     * Variante con metadatos para quality-service: tipologia, marca de muestreo ciego y campos corregidos (solo nombre
     * de campo y tipo de correccion, jamas valores: SEC-050).
     */
    public void completada(ReviewTask task, String action, String reviewerId, String secondReviewerId,
                           boolean criticalCorrection, String typology, boolean blindSample,
                           List<Correction> corrections) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", task.documentId().toString());
        p.put("taskId", task.id().toString());
        p.put("action", action);
        p.put("reviewerId", reviewerId);
        if (secondReviewerId != null) {
            p.put("secondReviewerId", secondReviewerId);
        }
        p.put("criticalCorrection", criticalCorrection);
        if (typology != null) {
            p.put("typology", typology);
        }
        if (blindSample) {
            p.put("blindSample", true);
        }
        ArrayNode corrected = null;
        for (Correction c : corrections) {
            String type = correctionType(c.originalValue(), c.correctedValue());
            if (type == null) {
                continue;
            }
            if (corrected == null) {
                corrected = p.putArray("correctedFields");
            }
            if (corrected.size() < MAX_CORRECTED) {
                corrected.addObject().put("field", fieldCode(c.fieldName())).put("correctionType", type);
            }
        }
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), "revision.completada", 1, Instant.now(),
                UUID.fromString(task.tenantId()), task.correlationId(), p);
        outbox.publish(task.documentId().toString(), e);
    }

    /** Escalamiento por SLA vencido (sin PII). {@code task} ya con el nuevo nivel; slaBreachedAt = vencimiento previo. */
    public void escalada(ReviewTask task, OffsetDateTime slaBreachedAt) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("documentId", task.documentId().toString());
        p.put("taskId", task.id().toString());
        p.put("level", task.escalationLevel());
        p.put("slaBreachedAt", slaBreachedAt.toInstant().toString());
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), "revision.escalada", 1, Instant.now(),
                UUID.fromString(task.tenantId()), task.correlationId(), p);
        outbox.publish(task.documentId().toString(), e);
    }

    /**
     * Clasifica la correccion sin exponer valores: null si no cambio; OMISION (faltaba el valor), SOBRANTE (no debia
     * haber valor), FORMATO (mismo contenido alfanumerico, distinta forma) o VALOR (contenido distinto).
     */
    static String correctionType(String original, String corrected) {
        String o = original == null ? "" : original.strip();
        String c = corrected == null ? "" : corrected.strip();
        if (o.equals(c)) {
            return null;
        }
        if (o.isEmpty()) {
            return "OMISION";
        }
        if (c.isEmpty()) {
            return "SOBRANTE";
        }
        return NON_ALNUM.matcher(o).replaceAll("").equalsIgnoreCase(NON_ALNUM.matcher(c).replaceAll(""))
                ? "FORMATO" : "VALOR";
    }

    /** Nombre de campo acorde al esquema (^[a-z][a-z0-9_]{0,63}$): minusculas, separadores a guion bajo. */
    static String fieldCode(String name) {
        String f = NON_CODE.matcher(name.toLowerCase(Locale.ROOT)).replaceAll("_");
        if (f.isEmpty() || !Character.isLetter(f.charAt(0))) {
            f = "f_" + f;
        }
        return f.length() > 64 ? f.substring(0, 64) : f;
    }

    private static final int MAX_CORRECTED = 64;
    private static final Pattern NON_ALNUM = Pattern.compile("[^\\p{L}\\p{N}]");
    private static final Pattern NON_CODE = Pattern.compile("[^a-z0-9_]");
}
