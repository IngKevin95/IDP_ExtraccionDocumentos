package com.idp.chat.infra;

import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Purga de todo lo derivado de un documento (SEC-022, Habeas Data): citas, mensajes (y con ellos la cache semantica),
 * sesiones, fragmentos (texto cifrado y embeddings) y estado de indexacion. Debe ejecutarse en UNA transaccion: el
 * trigger de inmutabilidad de chat_message y citation solo admite el DELETE si la transaccion fijo
 * {@code idp.purge_document_id} al documento al que pertenecen las filas (nunca otro documento, nunca UPDATE).
 */
@Repository
public class DocumentPurgeRepository {

    /** Filas borradas por tabla. */
    public record Purged(int citations, int messages, int sessions, int chunks, int index) {
        public int total() {
            return citations + messages + sessions + chunks + index;
        }
    }

    private final JdbcTemplate jdbc;

    public DocumentPurgeRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Purged purge(UUID documentId) {
        jdbc.queryForObject("select set_config('idp.purge_document_id', ?, true)", String.class,
                documentId.toString());
        int citations = jdbc.update("delete from citation where message_id in (select m.id from chat_message m "
                + "join chat_session s on s.id = m.session_id where s.document_id = ?)", documentId);
        int messages = jdbc.update("delete from chat_message where session_id in (select id from chat_session "
                + "where document_id = ?)", documentId);
        int sessions = jdbc.update("delete from chat_session where document_id = ?", documentId);
        int chunks = jdbc.update("delete from chunk where document_id = ?", documentId);
        int index = jdbc.update("delete from document_index_status where document_id = ?", documentId);
        return new Purged(citations, messages, sessions, chunks, index);
    }
}
