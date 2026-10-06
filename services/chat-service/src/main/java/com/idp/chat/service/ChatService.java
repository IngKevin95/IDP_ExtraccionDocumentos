package com.idp.chat.service;

import com.idp.chat.config.ChatProperties;
import com.idp.chat.domain.ChatAnswer;
import com.idp.chat.domain.ChatSession;
import com.idp.chat.domain.Chunk;
import com.idp.chat.domain.Citation;
import com.idp.chat.domain.Outcome;
import com.idp.chat.infra.ChatRepository;
import com.idp.chat.infra.ChatRepository.AnswerMeta;
import com.idp.chat.infra.ChatRepository.CachedAnswer;
import com.idp.chat.infra.ChatRepository.NewCitation;
import com.idp.chat.infra.ChatRepository.StoredCitation;
import com.idp.chat.infra.ChunkRepository;
import com.idp.chat.infra.ResilientLlm;
import com.idp.chat.infra.SecurityEvents;
import com.idp.chat.infra.TokenUsageRepository;
import com.idp.chat.service.Exceptions.AccessDeniedException;
import com.idp.chat.service.Exceptions.InvalidRequestException;
import com.idp.chat.service.Exceptions.LlmUnavailableException;
import com.idp.chat.service.Exceptions.PromptInjectionException;
import com.idp.chat.service.Exceptions.SessionNotFoundException;
import com.idp.events.EventOriginGuard;
import com.idp.llm.EmbeddingProvider;
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
 * Casos de uso del chat documental. Orden de cada mensaje: identidad (JWT revalidado) -> propiedad de la sesion
 * (404 uniforme) -> acceso al documento (antes de tocar el indice vectorial, AC-08) -> limites de tasa y cuota diaria
 * (antes de gastar embeddings o LLM) -> prompt injection -> cache semantica -> recuperacion acotada al documento -> LLM
 * acotado (timeout, bulkhead, failover) -> grounding de citas y de cifras/fechas -> persistencia cifrada. Ninguna
 * transaccion de base cubre una llamada al proveedor de embeddings, al LLM ni al KMS. Fallo cerrado: sin tenant en el
 * contexto no se hace nada.
 */
@Service
public class ChatService {

    private static final Logger LOG = LoggerFactory.getLogger(ChatService.class);

    /** Datos de un turno ya autorizado: identidad, sesion y huellas de la pregunta. */
    private record Turn(UUID tenant, UUID correlationId, ChatSession session, String content, float[] question,
                        QuestionFingerprint fingerprint) {
        String tenantId() {
            return tenant.toString();
        }

        UUID documentId() {
            return session.documentId();
        }
    }

    private final CallerResolver callers;
    private final ChatRepository chats;
    private final ChunkRepository chunks;
    private final DocumentAccessChecker access;
    private final PromptInjectionDetector detector;
    private final PromptBuilder prompts;
    private final GroundingVerifier grounding;
    private final EmbeddingProvider embeddings;
    private final ResilientLlm llm;
    private final SecurityEvents events;
    private final TransactionTemplate tx;
    private final ChatProperties props;
    private final ContentCipher cipher;
    private final ChatLimiter limiter;
    private final EmbeddingCache embeddingCache;
    private final TokenUsageRepository usage;
    private final ChatConfigFingerprint config;

    public ChatService(CallerResolver callers, ChatRepository chats, ChunkRepository chunks,
                       DocumentAccessChecker access, PromptInjectionDetector detector, PromptBuilder prompts,
                       GroundingVerifier grounding, EmbeddingProvider embeddings, ResilientLlm llm,
                       SecurityEvents events, TransactionTemplate tx, ChatProperties props, ContentCipher cipher,
                       ChatLimiter limiter, EmbeddingCache embeddingCache, TokenUsageRepository usage,
                       ChatConfigFingerprint config) {
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
        this.cipher = cipher;
        this.limiter = limiter;
        this.embeddingCache = embeddingCache;
        this.usage = usage;
        this.config = config;
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
            // SEC-003: 404 uniforme (no se distingue "no existe" de "es de otro usuario"); la denegacion se audita.
            events.isolatedAccessDenied(tenant, correlationId, sessionId.toString(),
                    SecurityEvents.INSUFFICIENT_PERMISSIONS);
            throw new SessionNotFoundException();
        }
        // AC-08 / SEC-007: el acceso al documento se revalida en cada mensaje, antes de embeber o buscar.
        assertDocumentAccess(caller, session.documentId());

        // SEC-049: tasa y cuota ANTES de gastar embeddings o LLM.
        limiter.checkRate(caller.tenantId(), caller.userId());
        limiter.checkQuota();

        Optional<String> injected = detector.detect(content);
        if (injected.isPresent()) {
            events.isolatedPromptInjection(tenant, correlationId, session.documentId(), sessionId, injected.get());
            LOG.warn(EventOriginGuard.SECURITY, "Prompt injection directo bloqueado: sesion={}", sessionId);
            throw new PromptInjectionException(correlationId);
        }

        TenantId tenantId = new TenantId(caller.tenantId());
        QuestionFingerprint fp = QuestionFingerprint.of(content);
        float[] question = embedQuestion(tenantId, fp, content);
        Turn turn = new Turn(tenant, correlationId, session, content, question, fp);

        Optional<CachedAnswer> hit = chats.nearestAnswered(session.documentId(), caller.tenantId(), caller.userId(),
                caller.privileged(), question, fp.hash(), fp.signature())
                .filter(h -> h.similarity() >= props.cacheSimilarity());
        if (hit.isPresent()) {
            return serveFromCache(turn, hit.get());
        }

        List<Chunk> usable = usableChunks(turn, decrypt(turn,
                chunks.findSimilar(session.documentId(), question, props.topK(), props.minSimilarity())));
        if (usable.isEmpty()) {
            return emitted(turn, PromptBuilder.ABSTENTION, Outcome.ABSTAINED, List.of(), noLlm());
        }

        LlmResponse response = generate(tenantId, prompts.build(content, usable, UUID.randomUUID()));
        String output = response == null ? null : response.content();
        AnswerMeta meta = llmMeta(response);
        if (output == null || output.isBlank()) {
            if (isContentFilter(response)) {
                return blocked(turn, SecurityEvents.CONTENT_FILTER_TRIGGERED, meta);
            }
            throw new LlmUnavailableException("El proveedor LLM no devolvio contenido");
        }

        GroundingVerifier.Result checked = grounding.verify(output, usable);
        if (checked.invalid() > 0) {
            return blocked(turn, SecurityEvents.SEVERE_HALLUCINATION, meta);
        }
        if (checked.citations().isEmpty()) {
            if (checked.abstention()) {
                // El texto del modelo se descarta: se responde la abstencion estandar.
                return emitted(turn, PromptBuilder.ABSTENTION, Outcome.ABSTAINED, List.of(), meta);
            }
            // SEC-031: una respuesta sin citas verificables no sale al cliente.
            return blocked(turn, SecurityEvents.SEVERE_HALLUCINATION, meta);
        }
        if (checked.ungrounded() > 0) {
            // SEC-048: cifras, fechas o monedas del texto libre ausentes de las citas y del fragmento citado.
            return blocked(turn, SecurityEvents.SEVERE_HALLUCINATION, meta);
        }
        return emitted(turn, checked.text(), Outcome.ANSWERED, checked.citations(), meta);
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

    /** Embedding de la pregunta, cacheado por hash de la pregunta normalizada (sin repetir la llamada al proveedor). */
    private float[] embedQuestion(TenantId tenantId, QuestionFingerprint fp, String content) {
        float[] cached = embeddingCache.get(tenantId.value(), fp.hash());
        if (cached != null) {
            return cached;
        }
        float[] v;
        try {
            v = embeddings.embed(tenantId, content);
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("Proveedor de embeddings no disponible", e);
        }
        if (v == null || v.length != props.embeddingDimension()) {
            throw new LlmUnavailableException("Embedding de pregunta con dimension invalida");
        }
        embeddingCache.put(tenantId.value(), fp.hash(), v);
        return v;
    }

    private LlmResponse generate(TenantId tenantId, String prompt) {
        try {
            return llm.generate(new LlmRequest(tenantId, prompt, List.of(), props.llmTimeout()));
        } catch (LlmUnavailableException | Exceptions.CapacityExceededException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmUnavailableException("Proveedor LLM no disponible", e);
        }
    }

    /** Descifra los fragmentos recuperados (solo se sirven descifrados; en la base viven como sobre del tenant). */
    private List<Chunk> decrypt(Turn t, List<ChunkRepository.Found> found) {
        List<Chunk> out = new ArrayList<>(found.size());
        for (ChunkRepository.Found f : found) {
            String text = cipher.decrypt(t.tenantId(), f.documentId(), f.id(), ContentCipher.FIELD_CHUNK,
                    f.contentEnc());
            out.add(new Chunk(f.id(), f.documentId(), f.ordinal(), f.pageNumber(), text, f.similarity()));
        }
        return out;
    }

    /** Descarta fragmentos con instrucciones inyectadas (indirecta, SEC-033) y emite una sola senal. */
    private List<Chunk> usableChunks(Turn t, List<Chunk> found) {
        List<Chunk> usable = new ArrayList<>(found.size());
        String flaggedRule = null;
        for (Chunk c : found) {
            Optional<String> rule = detector.detect(c.content());
            if (rule.isPresent()) {
                flaggedRule = flaggedRule == null ? rule.get() : flaggedRule;
            } else {
                usable.add(c);
            }
        }
        if (flaggedRule != null) {
            events.isolatedPromptInjection(t.tenant(), t.correlationId(), t.documentId(), t.session().id(),
                    flaggedRule);
            LOG.warn(EventOriginGuard.SECURITY, "Prompt injection indirecto en fragmentos: documento={}",
                    t.documentId());
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

    /** Respuesta sin llamada al LLM: solo version de prompt y huella de configuracion (SEC-049). */
    private AnswerMeta noLlm() {
        return new AnswerMeta(null, PromptBuilder.PROMPT_VERSION, config.hash(), null, null);
    }

    /** Version de modelo (la informada por el proveedor o, si falta, la configurada), prompt, config y tokens. */
    private AnswerMeta llmMeta(LlmResponse response) {
        String reported = response == null ? null : response.modelVersion();
        String model = reported == null || reported.isBlank() ? props.llm().model() : reported;
        LlmResponse.Usage u = response == null ? null : response.usage();
        return new AnswerMeta(truncate(model), PromptBuilder.PROMPT_VERSION, config.hash(),
                u == null ? null : u.inputTokens(), u == null ? null : u.outputTokens());
    }

    private static String truncate(String s) {
        return s.length() <= 128 ? s : s.substring(0, 128);
    }

    private ChatAnswer blocked(Turn t, String reasonCode, AnswerMeta meta) {
        return exchange(t, PromptBuilder.ABSTENTION, Outcome.BLOCKED, List.of(), null, meta,
                messageId -> events.responseBlocked(t.tenant(), t.correlationId(), t.session().id(), reasonCode));
    }

    /** ANSWERED o ABSTAINED: ademas de persistir, emite la senal de auditoria con versiones y tokens. */
    private ChatAnswer emitted(Turn t, String answer, Outcome outcome, List<Citation> citations, AnswerMeta meta) {
        return exchange(t, answer, outcome, citations, null, meta, messageId -> events.responseEmitted(t.tenant(),
                t.correlationId(), t.session().id(), messageId, outcome.name(), meta));
    }

    private ChatAnswer serveFromCache(Turn t, CachedAnswer hit) {
        String text = cipher.decrypt(t.tenantId(), t.documentId(), hit.answerId(), ContentCipher.FIELD_MESSAGE,
                hit.contentEnc());
        List<Citation> citations = new ArrayList<>();
        for (StoredCitation c : chats.citationsOf(hit.answerId())) {
            citations.add(new Citation(c.chunkId(), c.pageNumber(), cipher.decrypt(t.tenantId(), t.documentId(),
                    c.id(), ContentCipher.FIELD_CITATION, c.quoteEnc())));
        }
        return exchange(t, text, Outcome.CACHED, citations, hit.answerId(), noLlm(),
                messageId -> events.servedFromCache(t.tenant(), t.correlationId(), t.session().id(),
                        hit.answerId()));
    }

    /**
     * Cifra (fuera de la transaccion: el KMS es una llamada remota) y persiste pregunta, respuesta, citas, tokens
     * consumidos y, opcionalmente, la senal de outbox, todo en una sola transaccion.
     */
    private ChatAnswer exchange(Turn t, String answer, Outcome outcome, List<Citation> citations,
                                UUID cachedFrom, AnswerMeta meta, Consumer<UUID> inTx) {
        UUID userMessage = UUID.randomUUID();
        UUID assistantMessage = UUID.randomUUID();
        byte[] questionEnc = cipher.encrypt(t.tenantId(), t.documentId(), userMessage, ContentCipher.FIELD_MESSAGE,
                t.content());
        byte[] answerEnc = cipher.encrypt(t.tenantId(), t.documentId(), assistantMessage,
                ContentCipher.FIELD_MESSAGE, answer);
        List<NewCitation> stored = new ArrayList<>(citations.size());
        for (Citation c : citations) {
            UUID id = UUID.randomUUID();
            stored.add(new NewCitation(id, c.chunkId(), cipher.encrypt(t.tenantId(), t.documentId(), id,
                    ContentCipher.FIELD_CITATION, c.exactQuote())));
        }
        tx.executeWithoutResult(status -> {
            chats.insertUserMessage(userMessage, t.session().id(), questionEnc, t.question(),
                    t.fingerprint().hash(), t.fingerprint().signature());
            chats.insertAssistantMessage(assistantMessage, t.session().id(), userMessage, answerEnc, outcome,
                    cachedFrom, meta);
            chats.insertCitations(assistantMessage, stored);
            if (meta.tokensIn() != null || meta.tokensOut() != null) {
                usage.add(meta.tokensIn() == null ? 0 : meta.tokensIn(), meta.tokensOut() == null ? 0
                        : meta.tokensOut());
            }
            if (inTx != null) {
                inTx.accept(assistantMessage);
            }
        });
        return new ChatAnswer(assistantMessage, answer, outcome, citations);
    }
}
