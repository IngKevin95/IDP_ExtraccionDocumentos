package com.idp.chat.infra;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.events.EventEnvelope;
import com.idp.events.OutboxPublisher;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publica por outbox las senales de audit.signals del chat (claim-check, SEC-050: solo UUIDs y codigos, nunca el
 * contenido del mensaje ni del documento). Los metodos {@code isolated*} usan una transaccion propia (REQUIRES_NEW):
 * la senal sale aunque el flujo que la origina termine en excepcion y revierta su transaccion. Los demas deben
 * invocarse dentro de la transaccion que persiste el hecho.
 */
@Component
public class SecurityEvents {

    private static final Logger LOG = LoggerFactory.getLogger(SecurityEvents.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public static final String UNAUTHORIZED_TENANT = "UNAUTHORIZED_TENANT";
    public static final String INSUFFICIENT_PERMISSIONS = "INSUFFICIENT_PERMISSIONS";
    public static final String CONTENT_FILTER_TRIGGERED = "CONTENT_FILTER_TRIGGERED";
    public static final String SEVERE_HALLUCINATION = "SEVERE_HALLUCINATION";

    private final OutboxPublisher outbox;
    private final TransactionTemplate isolated;

    public SecurityEvents(OutboxPublisher outbox, PlatformTransactionManager txManager) {
        this.outbox = outbox;
        this.isolated = new TransactionTemplate(txManager);
        this.isolated.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    // ---- senales que no deben perderse con un rollback ---------------------------------------------------------

    /** seguridad.acceso_denegado. Un fallo al publicar se registra y no oculta la denegacion. */
    public void isolatedAccessDenied(UUID tenantId, UUID correlationId, String resourceId, String reasonCode) {
        isolated("acceso_denegado", () -> {
            ObjectNode p = MAPPER.createObjectNode();
            p.put("resourceId", resourceId);
            p.put("reasonCode", reasonCode);
            emit("seguridad.acceso_denegado", tenantId, correlationId, p);
        });
    }

    /** seguridad.prompt_injection_detectado (SEC-033). */
    public void isolatedPromptInjection(UUID tenantId, UUID correlationId, UUID documentId, UUID sessionId,
                                        String ruleId) {
        isolated("prompt_injection", () -> {
            ObjectNode p = MAPPER.createObjectNode();
            p.put("documentId", documentId.toString());
            p.put("source", "CHAT");
            if (sessionId != null) {
                p.put("sessionId", sessionId.toString());
            }
            if (ruleId != null) {
                p.put("ruleId", ruleId);
            }
            emit("seguridad.prompt_injection_detectado", tenantId, correlationId, p);
        });
    }

    // ---- senales ligadas a la transaccion de persistencia --------------------------------------------------------

    public void responseBlocked(UUID tenantId, UUID correlationId, UUID sessionId, String reasonCode) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("sessionId", sessionId.toString());
        p.put("reasonCode", reasonCode);
        emit("chat.respuesta_bloqueada", tenantId, correlationId, p);
    }

    /** chat.respuesta_emitida (ANSWERED/ABSTAINED): version de modelo, prompt y configuracion, solo huellas y UUID. */
    public void responseEmitted(UUID tenantId, UUID correlationId, UUID sessionId, UUID messageId, String outcome,
                                ChatRepository.AnswerMeta meta) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("sessionId", sessionId.toString());
        p.put("messageId", messageId.toString());
        p.put("outcome", outcome);
        if (meta.model() != null) {
            p.put("model", meta.model());
        }
        p.put("promptVersion", meta.promptVersion());
        p.put("configHash", meta.configHash());
        if (meta.tokensIn() != null) {
            p.put("tokensIn", meta.tokensIn());
        }
        if (meta.tokensOut() != null) {
            p.put("tokensOut", meta.tokensOut());
        }
        emit("chat.respuesta_emitida", tenantId, correlationId, p);
    }

    public void servedFromCache(UUID tenantId, UUID correlationId, UUID sessionId, UUID originalMessageId) {
        ObjectNode p = MAPPER.createObjectNode();
        p.put("sessionId", sessionId.toString());
        p.put("originalMessageId", originalMessageId.toString());
        emit("chat.respuesta_desde_cache", tenantId, correlationId, p);
    }

    private void emit(String type, UUID tenantId, UUID correlationId, ObjectNode payload) {
        EventEnvelope e = new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), tenantId, correlationId,
                payload);
        outbox.publish(tenantId.toString(), e);
    }

    private void isolated(String what, Runnable work) {
        try {
            isolated.executeWithoutResult(status -> work.run());
        } catch (RuntimeException ex) {
            LOG.error("No se pudo publicar la senal {}: {}", what, ex.getClass().getSimpleName());
        }
    }
}
