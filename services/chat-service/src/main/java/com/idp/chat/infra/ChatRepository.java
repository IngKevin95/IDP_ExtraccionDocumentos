package com.idp.chat.infra;

import com.idp.chat.domain.ChatSession;
import com.idp.chat.domain.Citation;
import com.idp.chat.domain.Outcome;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** Sesiones, mensajes y citas del silo del tenant actual. Mensajes y citas son append-only (trigger en la base). */
@Repository
public class ChatRepository {

    /** Respuesta previa elegible para la cache semantica. */
    public record CachedAnswer(UUID answerId, String content, double similarity) {
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

    public void insertUserMessage(UUID id, UUID sessionId, String content, float[] questionEmbedding) {
        jdbc.update("insert into chat_message (id, session_id, role, content, created_at, question_embedding) "
                + "values (?, ?, 'user', ?, ?, cast(? as vector))", id, sessionId, content, now(),
                Vectors.literal(questionEmbedding));
    }

    public void insertAssistantMessage(UUID id, UUID sessionId, UUID replyTo, String content, Outcome outcome,
                                       UUID cachedFrom) {
        jdbc.update("insert into chat_message (id, session_id, role, content, created_at, reply_to, outcome, "
                + "cached_from) values (?, ?, 'assistant', ?, ?, ?, ?, ?)", id, sessionId, content, now(), replyTo,
                outcome.name(), cachedFrom);
    }

    public void insertCitations(UUID messageId, List<Citation> citations) {
        int ordinal = 0;
        for (Citation c : citations) {
            jdbc.update("insert into citation (id, message_id, chunk_id, exact_quote, ordinal) values (?, ?, ?, ?, ?)",
                    UUID.randomUUID(), messageId, c.chunkId(), c.exactQuote(), ordinal++);
        }
    }

    public List<Citation> citationsOf(UUID messageId) {
        return jdbc.query("select c.chunk_id, k.page_number, c.exact_quote from citation c "
                + "join chunk k on k.id = c.chunk_id where c.message_id = ? order by c.ordinal",
                (rs, i) -> new Citation(rs.getObject("chunk_id", UUID.class), rs.getInt("page_number"),
                        rs.getString("exact_quote")), messageId);
    }

    /**
     * Pregunta mas parecida ya respondida con citas (ANSWERED) por el mismo usuario sobre el mismo documento. El
     * llamador aplica el umbral; la autorizacion se revalida antes de consultar (misma regla que una pregunta nueva).
     */
    public Optional<CachedAnswer> nearestAnswered(UUID documentId, String userId, float[] question) {
        String vector = Vectors.literal(question);
        return jdbc.query("select a.id, a.content, 1 - (u.question_embedding <=> cast(? as vector)) as similarity "
                + "from chat_message u join chat_session s on s.id = u.session_id "
                + "join chat_message a on a.reply_to = u.id and a.outcome = 'ANSWERED' "
                + "where s.document_id = ? and s.user_id = ? and u.role = 'user' and u.question_embedding is not null "
                + "order by u.question_embedding <=> cast(? as vector) limit 1",
                (rs, i) -> new CachedAnswer(rs.getObject("id", UUID.class), rs.getString("content"),
                        rs.getDouble("similarity")), vector, documentId, userId, vector).stream().findFirst();
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }
}
