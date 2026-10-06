package com.idp.chat.service;

import com.idp.chat.config.ChatProperties;
import com.idp.chat.domain.ChatAnswer;
import com.idp.chat.domain.ChatSession;
import com.idp.chat.domain.Chunk;
import com.idp.chat.domain.Citation;
import com.idp.chat.domain.Outcome;
import com.idp.chat.infra.ChatRepository;
import com.idp.chat.infra.ChatRepository.CachedAnswer;
import com.idp.chat.infra.ChunkRepository;
import com.idp.chat.infra.SecurityEvents;
import com.idp.chat.service.Exceptions.AccessDeniedException;
import com.idp.chat.service.Exceptions.InvalidRequestException;
import com.idp.chat.service.Exceptions.LlmUnavailableException;
import com.idp.chat.service.Exceptions.PromptInjectionException;
import com.idp.chat.service.Exceptions.SessionNotFoundException;
import com.idp.events.EventOriginGuard;
import com.idp.llm.EmbeddingProvider;
import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Casos de uso del chat documental. Orden de cada mensaje: identidad (JWT revalidado) -> propiedad de la sesion ->
 * acceso al documento (antes de tocar el indice vectorial, AC-08) -> prompt injection -> cache semantica -> recuperacion
 * acotada al documento -> LLM -> verificacion de grounding -> persistencia. Ninguna transaccion de base cubre una
 * llamada al proveedor de embeddings o al LLM. Fallo cerrado: sin tenant en el contexto no se hace nada.
 */
@Service
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    private final CallerResolver callers;
    private final ChatRepository chats;
    private final ChunkRepository chunks;
    private final DocumentAccessChecker access;
    private final PromptInjectionDetector detector;
    private final PromptBuilder prompts;
    private final GroundingVerifier grounding;
    private final EmbeddingProvider embeddings;
    private final LlmProvider llm;
    private final SecurityEvents events;
    private final TransactionTemplate tx;
    private final ChatProperties props;

    public ChatService(CallerResolver callers, ChatRepository chats, ChunkRepository chunks,
                       DocumentAccessChecker access, PromptInjectionDetector detector, PromptBuilder prompts,
                       GroundingVerifier grounding, EmbeddingProvider embeddings, LlmProvider llm,
                       SecurityEvents events, TransactionTemplate tx, ChatProperties props) {
        this.callers = callers;
        this.chats = chats;
        this.chunks = chunks;
        this.access = access;
        this.detector = detector;
        this.prompts = prompts;
        this.grounding = grounding;
        this.embeddings = embeddings;
        this.llm = llm;
        this.events = events;
        this.tx = tx;
        this.props = props;
    }

    // ---- crear sesion ------------------------------------------------------------------------------------------

    public ChatSession createSession(Jwt jwt, UUID documentId) {
        Caller caller = resolve(jwt, documentId.toString());
        requireTenantContext(caller);
        assertDocumentAccess(caller, documentId);
        return chats.insertSession(documentId, caller.userId());
    }

    // ---- enviar mensaje ----------------------------------------------------------------------------------------

    public ChatAnswer sendMessage(Jwt jwt, UUID sessionId, String content) {
        if (content == null || content.isBlank()) {
            throw new InvalidRequestException("El mensaje no puede estar vacio");
        }
        if (content.length() > props.maxMessageChars()) {
            throw new InvalidRequestException("El mensaje excede el largo maximo");
        }
        Caller caller = resolve(jwt, sessionId.toString());
        requireTenantContext(caller);
        UUID tenant = UUID.fromString(caller.tenantId());
        UUID correlationId = UUID.randomUUID();

        ChatSession session = chats.findSession(sessionId).orElseThrow(SessionNotFoundException::new);
        if (!session.userId().equals(caller.userId()) || !session.active()) {
            events.isolatedAccessDenied(tenant, correlationId, sessionId.toString(),
                    SecurityEvents.INSUFFICIENT_PERMISSIONS);
            throw new AccessDeniedException("Sin acceso a la sesion");
        }
        // AC-08 / SEC-007: el acceso al documento se revalida en cada mensaje, antes de embeber o buscar.
        assertDocumentAccess(caller, session.documentId());

        if (detector.detect(content).isPresent()) {
            events.isolatedPromptInjection(tenant, correlationId, session.documentId(), sessionId);
            LOG.warn(EventOriginGuard.SECURITY, "Prompt injection directo bloqueado: sesion={}", sessionId);
            throw new PromptInjectionException(correlationId);
        }

        TenantId tenantId = new TenantId(caller.tenantId());
        float[] question = embedQuestion(tenantId, content);

        Optional<CachedAnswer> hit = chats.nearestAnswered(session.documentId(), caller.userId(), question)
                .filter(h -> h.similarity() >= props.cacheSimilarity());
        if (hit.isPresent()) {
            return serveFromCache(tenant, correlationId, session, content, question, hit.get());
        }

        List<Chunk> usable = usableChunks(tenant, correlationId, session,
                chunks.findSimilar(session.documentId(), question, props.topK(), props.minSimilarity()));
        if (usable.isEmpty()) {
            return exchange(session, content, question, PromptBuilder.ABSTENTION, Outcome.ABSTAINED, List.of(), null);
        }

        LlmResponse response = generate(tenantId, prompts.build(content, usable, UUID.randomUUID()));
        String output = response == null ? null : response.content();
        if (output == null || output.isBlank()) {
            if (isContentFilter(response)) {
                return blocked(tenant, correlationId, session, content, question,
                        SecurityEvents.CONTENT_FILTER_TRIGGERED);
            }
            throw new LlmUnavailableException("El proveedor LLM no devolvio contenido");
        }

        GroundingVerifier.Result checked = grounding.verify(output, usable);
        if (checked.invalid() > 0) {
            return blocked(tenant, correlationId, session, content, question, SecurityEvents.SEVERE_HALLUCINATION);
        }
        if (checked.citations().isEmpty()) {
            if (checked.abstention()) {
                return exchange(session, content, question, PromptBuilder.ABSTENTION, Outcome.ABSTAINED, List.of(),
                        null);
            }
            // SEC-031: una respuesta sin citas verificables no sale al cliente.
            return blocked(tenant, correlationId, session, content, question, SecurityEvents.SEVERE_HALLUCINATION);
        }
        return exchange(session, content, question, checked.text(), Outcome.ANSWERED, checked.citations(), null);
    }

    // ---- piezas ------------------------------------------------------------------------------------------------

    private Caller resolve(Jwt jwt, String resourceId) {
        try {
            return callers.require(jwt);
        } catch (AccessDeniedException e) {
            String claim = jwt == null ? null : jwt.getClaimAsString("tenant_id");
            String tenant = CallerResolver.normalizedTenant(claim);
            if (tenant != null && tenant.equals(claim)) {
                events.isolatedAccessDenied(UUID.fromString(tenant), UUID.randomUUID(), resourceId,
                        SecurityEvents.INSUFFICIENT_PERMISSIONS);
            }
            throw e;
        }
    }

    /** Fallo cerrado: el contexto de tenant (que enruta el silo) debe existir y coincidir con el del token. */
    private static void requireTenantContext(Caller caller) {
        String ctx = TenantContextHolder.getTenantId();
        if (ctx == null || !ctx.equals(caller.tenantId())) {
            throw new AccessDeniedException("Contexto de tenant invalido");
        }
    }

    private void assertDocumentAccess(Caller caller, UUID documentId) {
        try {
            access.assertCanRead(caller, documentId);
        } catch (AccessDeniedException e) {
            events.isolatedAccessDenied(UUID.fromString(caller.tenantId()), UUID.randomUUID(),
                    documentId.toString(), SecurityEvents.INSUFFICIENT_PERMISSIONS);
            throw e;
        }
    }

    private float[] embedQuestion(TenantId tenantId, String content) {
        float[] v;
        try {
            v = embeddings.embed(tenantId, content);
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("Proveedor de embeddings no disponible", e);
        }
        if (v == null || v.length != props.embeddingDimension()) {
            throw new LlmUnavailableException("Embedding de pregunta con dimension invalida");
        }
        return v;
    }

    private LlmResponse generate(TenantId tenantId, String prompt) {
        try {
            return llm.generate(new LlmRequest(tenantId, prompt, List.of(), props.llmTimeout()));
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("Proveedor LLM no disponible", e);
        }
    }

    /** Descarta fragmentos con instrucciones inyectadas (indirecta, SEC-033) y emite una sola senal. */
    private List<Chunk> usableChunks(UUID tenant, UUID correlationId, ChatSession session, List<Chunk> found) {
        List<Chunk> usable = new ArrayList<>(found.size());
        boolean flagged = false;
        for (Chunk c : found) {
            if (detector.detect(c.content()).isPresent()) {
                flagged = true;
            } else {
                usable.add(c);
            }
        }
        if (flagged) {
            events.isolatedPromptInjection(tenant, correlationId, session.documentId(), session.id());
            LOG.warn(EventOriginGuard.SECURITY, "Prompt injection indirecto en fragmentos: documento={}",
                    session.documentId());
        }
        return usable;
    }

    private static boolean isContentFilter(LlmResponse r) {
        if (r == null || r.finishReason() == null) {
            return false;
        }
        String f = r.finishReason().toLowerCase(Locale.ROOT);
        return f.contains("content_filter") || f.contains("safety") || f.contains("blocklist");
    }

    private ChatAnswer blocked(UUID tenant, UUID correlationId, ChatSession session, String content, float[] question,
                               String reasonCode) {
        return exchange(session, content, question, PromptBuilder.ABSTENTION, Outcome.BLOCKED, List.of(),
                messageId -> events.responseBlocked(tenant, correlationId, session.id(), reasonCode));
    }

    private ChatAnswer serveFromCache(UUID tenant, UUID correlationId, ChatSession session, String content,
                                      float[] question, CachedAnswer hit) {
        List<Citation> citations = chats.citationsOf(hit.answerId());
        return exchange(session, content, question, hit.content(), Outcome.CACHED, citations, hit.answerId(),
                messageId -> events.servedFromCache(tenant, correlationId, session.id(), hit.answerId()));
    }

    private ChatAnswer exchange(ChatSession session, String content, float[] question, String answer,
                                Outcome outcome, List<Citation> citations, Consumer<UUID> inTx) {
        return exchange(session, content, question, answer, outcome, citations, null, inTx);
    }

    /** Persiste pregunta, respuesta, citas y (opcional) la senal de outbox en una sola transaccion. */
    private ChatAnswer exchange(ChatSession session, String content, float[] question, String answer,
                                Outcome outcome, List<Citation> citations, UUID cachedFrom, Consumer<UUID> inTx) {
        UUID userMessage = UUID.randomUUID();
        UUID assistantMessage = UUID.randomUUID();
        tx.executeWithoutResult(status -> {
            chats.insertUserMessage(userMessage, session.id(), content, question);
            chats.insertAssistantMessage(assistantMessage, session.id(), userMessage, answer, outcome, cachedFrom);
            chats.insertCitations(assistantMessage, citations);
            if (inTx != null) {
                inTx.accept(assistantMessage);
            }
        });
        return new ChatAnswer(assistantMessage, answer, outcome, citations);
    }
}
