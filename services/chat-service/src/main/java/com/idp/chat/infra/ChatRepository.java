package com.idp.chat.infra;

import com.idp.chat.domain.ChatSession;
import com.idp.chat.domain.Outcome;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Sesiones, mensajes y citas del silo del tenant actual. Mensajes y citas son append-only (trigger en la base). El
 * contenido llega y sale cifrado (bytea): el cifrado con el sobre del tenant lo hace el servicio, nunca la base.
 */
@Repository
public class ChatRepository {

    /** Respuesta previa elegible para la cache semantica (contenido aun cifrado). */
    public record CachedAnswer(UUID answerId, byte[] contentEnc, double similarity) {
    }

    /** Version del modelo, del prompt y de la configuracion con la que se genero una respuesta (SEC-049). */
    public record AnswerMeta(String model, String promptVersion, String configHash, Integer tokensIn,
                             Integer tokensOut) {
        public static final AnswerMeta NONE = new AnswerMeta(null, null, null, null, null);
    }

    public record NewCitation(UUID id, UUID chunkId, byte[] quoteEnc) {
    }

    public record StoredCitation(UUID id, UUID chunkId, int pageNumber, byte[] quoteEnc) {
    }

    private static final RowMapper<ChatSession> SESSION = (rs, i) -> new ChatSession(rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class), rs.getString("user_id"),
            rs.getObject("created_at", OffsetDateTime.class), rs.getBoolean("active"));

    private final JdbcTemplate jdbc;

    public ChatRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public ChatSession insertSession(UUID documentId, String userId) {
        ChatSession s = new ChatSession(UUID.randomUUID(), documentId, userId, now(), true);
        jdbc.update("insert into chat_session (id, document_id, user_id, created_at, active) values (?, ?, ?, ?, ?)",
                s.id(), s.documentId(), s.userId(), s.createdAt(), s.active());
        return s;
    }

    public Optional<ChatSession> findSession(UUID id) {
        return jdbc.query("select id, document_id, user_id, created_at, active from chat_session where id = ?",
                SESSION, id).stream().findFirst();
    }

    public void insertUserMessage(UUID id, UUID sessionId, byte[] contentEnc, float[] questionEmbedding,
                                  String questionHash, String questionSig) {
        jdbc.update("insert into chat_message (id, session_id, role, content_enc, created_at, question_embedding, "
                + "question_hash, question_sig) values (?, ?, 'user', ?, ?, cast(? as vector), ?, ?)", id, sessionId,
                contentEnc, now(), Vectors.literal(questionEmbedding), questionHash, questionSig);
    }

    public void insertAssistantMessage(UUID id, UUID sessionId, UUID replyTo, byte[] contentEnc, Outcome outcome,
                                       UUID cachedFrom, AnswerMeta meta) {
        jdbc.update("insert into chat_message (id, session_id, role, content_enc, created_at, reply_to, outcome, "
                + "cached_from, model, prompt_version, config_hash, tokens_in, tokens_out) "
                + "values (?, ?, 'assistant', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", id, sessionId, contentEnc, now(),
                replyTo, outcome.name(), cachedFrom, meta.model(), meta.promptVersion(), meta.configHash(),
                meta.tokensIn(), meta.tokensOut());
    }

    public void insertCitations(UUID messageId, List<NewCitation> citations) {
        int ordinal = 0;
        for (NewCitation c : citations) {
            jdbc.update("insert into citation (id, message_id, chunk_id, exact_quote_enc, ordinal) "
                    + "values (?, ?, ?, ?, ?)", c.id(), messageId, c.chunkId(), c.quoteEnc(), ordinal++);
        }
    }

    public List<StoredCitation> citationsOf(UUID messageId) {
        return jdbc.query("select c.id, c.chunk_id, k.page_number, c.exact_quote_enc from citation c "
                + "join chunk k on k.id = c.chunk_id where c.message_id = ? order by c.ordinal",
                (rs, i) -> new StoredCitation(rs.getObject("id", UUID.class), rs.getObject("chunk_id", UUID.class),
                        rs.getInt("page_number"), rs.getBytes("exact_quote_enc")), messageId);
    }

    /**
     * Pregunta mas parecida ya respondida con citas (ANSWERED) por el mismo usuario sobre el mismo documento, con
     * pregunta normalizada identica ({@code questionHash}) o mismos tokens significativos ({@code questionSig}). La
     * misma consulta revalida que la sesion siga activa, que el documento siga indexado, vigente (no purgado) y que
     * su clasificacion actual siga permitiendo al usuario verlo. El llamador aplica el umbral de similitud.
     */
    public Optional<CachedAnswer> nearestAnswered(UUID documentId, String tenantId, String userId, boolean privileged,
                                                  float[] question, String questionHash, String questionSig) {
        String vector = Vectors.literal(question);
        return jdbc.query("select a.id, a.content_enc, 1 - (u.question_embedding <=> cast(? as vector)) as similarity "
                + "from chat_message u join chat_session s on s.id = u.session_id and s.active "
                + "join chat_message a on a.reply_to = u.id and a.outcome = 'ANSWERED' and a.content_enc is not null "
                + "join document_index_status d on d.document_id = s.document_id "
                + "join document doc on doc.id = s.document_id and doc.tenant_id = ? and doc.purged_at is null "
                + "where s.document_id = ? and s.user_id = ? and u.role = 'user' and u.question_embedding is not null "
                + "and (u.question_hash = ? or u.question_sig = ?) "
                + "and (doc.classification <> 'ALTAMENTE_CONFIDENCIAL' or ? or doc.uploaded_by = ?) "
                + "and exists (select 1 from citation c join chunk k on k.id = c.chunk_id where c.message_id = a.id) "
                + "order by u.question_embedding <=> cast(? as vector) limit 1",
                (rs, i) -> new CachedAnswer(rs.getObject("id", UUID.class), rs.getBytes("content_enc"),
                        rs.getDouble("similarity")), vector, tenantId, documentId, userId, questionHash, questionSig,
                privileged, userId, vector).stream().findFirst();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
