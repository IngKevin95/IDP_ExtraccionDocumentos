package com.idp.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.chat.infra.ChatRepository;
import com.idp.chat.infra.ChunkRepository;
import com.idp.chat.infra.ChunkRepository.NewChunk;
import com.idp.chat.domain.Outcome;
import com.idp.chat.domain.Citation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;

/** Migraciones sobre pgvector real: esquema de la spec seccion 6, indices, restricciones e inmutabilidad (spec seccion 4). */
class ChatSchemaIntegrationTest extends AbstractChatIntegrationTest {

    @Autowired ChatRepository chats;
    @Autowired ChunkRepository chunks;

    private static float[] axis(int i) {
        float[] v = new float[8];
        v[i] = 1f;
        return v;
    }

    /** Documento indexado a mano con un chunk por eje; devuelve el id del primer chunk de cada eje. */
    private UUID seedDocument(UUID doc) {
        return inTenant(tenant, () -> {
            UUID first = UUID.randomUUID();
            chunks.insertIndexed(doc, List.of(new NewChunk(first, 0, 1, "a", axis(0)),
                    new NewChunk(UUID.randomUUID(), 1, 2, "b", axis(1))));
            return first;
        });
    }

    private UUID seedAnswer(UUID doc, UUID chunkId) {
        return inTenant(tenant, () -> {
            var s = chats.insertSession(doc, "ana");
            UUID q = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            chats.insertUserMessage(q, s.id(), "pregunta", axis(0));
            chats.insertAssistantMessage(a, s.id(), q, "respuesta", Outcome.ANSWERED, null);
            chats.insertCitations(a, List.of(new Citation(chunkId, 1, "a")));
            return a;
        });
    }

    @Test
    void lasCitasSeLeenEnElOrdenOriginalQueApuntanLosMarcadores() {
        UUID chunk = seedDocument(UUID.randomUUID());
        List<Citation> original = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            original.add(new Citation(chunk, 1, "a" + i));
        }
        UUID answer = inTenant(tenant, () -> {
            var s = chats.insertSession(UUID.randomUUID(), "ana");
            UUID q = UUID.randomUUID();
            UUID a = UUID.randomUUID();
            chats.insertUserMessage(q, s.id(), "pregunta", axis(0));
            chats.insertAssistantMessage(a, s.id(), q, "respuesta [1] [2]", Outcome.ANSWERED, null);
            chats.insertCitations(a, original);
            return a;
        });
        assertThat(inTenant(tenant, () -> chats.citationsOf(answer))).isEqualTo(original);
    }

    @Test
    void elEsquemaCoincideConLaSpecSeccion6() {
        assertThat(columns("document_index_status")).containsExactlyInAnyOrder("document_id", "status", "indexed_at");
        assertThat(columns("chunk")).contains("id", "document_id", "page_number", "content", "embedding", "ordinal");
        assertThat(columns("chat_session")).containsExactlyInAnyOrder("id", "document_id", "user_id", "created_at",
                "active");
        assertThat(columns("chat_message")).contains("id", "session_id", "role", "content", "created_at");
        assertThat(columns("citation")).containsExactlyInAnyOrder("id", "message_id", "chunk_id", "exact_quote",
                "ordinal");
        assertThat(inTenant(tenant, () -> jdbc.queryForObject("select count(*) from pg_extension where extname = 'vector'",
                Integer.class))).isEqualTo(1);
    }

    private List<String> columns(String table) {
        return inTenant(tenant, () -> jdbc.queryForList("select column_name from information_schema.columns "
                + "where table_name = ?", String.class, table));
    }

    @Test
    void indicesHnswCosenoYBtreePorDocumentoYSesion() {
        List<String> defs = inTenant(tenant, () -> jdbc.queryForList("select indexdef from pg_indexes "
                + "where tablename in ('chunk','chat_session','chat_message')", String.class));

        assertThat(defs).anySatisfy(d -> assertThat(d).contains("USING hnsw").contains("vector_cosine_ops"));
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("USING btree (document_id)"));
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("(user_id, document_id)"));
        assertThat(defs).anySatisfy(d -> assertThat(d).contains("(session_id, created_at)"));
    }

    @Test
    void restriccionesDeIntegridad() {
        UUID doc = UUID.randomUUID();
        seedDocument(doc);

        // (document_id, ordinal) unico: un re-indexado parcial no duplica.
        assertThatThrownBy(() -> inTenant(tenant, () -> jdbc.update("insert into chunk (id, document_id, ordinal, "
                + "page_number, content, embedding) values (?, ?, 0, 1, 'x', cast(? as vector))", UUID.randomUUID(),
                doc, "[1,0,0,0,0,0,0,0]"))).isInstanceOf(DuplicateKeyException.class);
        // Chunk huerfano: sin estado de indexacion del documento.
        assertThatThrownBy(() -> inTenant(tenant, () -> jdbc.update("insert into chunk (id, document_id, ordinal, "
                + "page_number, content, embedding) values (?, ?, 0, 1, 'x', cast(? as vector))", UUID.randomUUID(),
                UUID.randomUUID(), "[1,0,0,0,0,0,0,0]"))).isInstanceOf(DataAccessException.class);
        // Dimension distinta a la del silo.
        assertThatThrownBy(() -> inTenant(tenant, () -> jdbc.update("insert into chunk (id, document_id, ordinal, "
                + "page_number, content, embedding) values (?, ?, 5, 1, 'x', cast(? as vector))", UUID.randomUUID(),
                doc, "[1,0,0]"))).isInstanceOf(DataAccessException.class);
        // Mensaje con sesion inexistente y rol invalido.
        assertThatThrownBy(() -> inTenant(tenant, () -> jdbc.update("insert into chat_message (id, session_id, role, "
                + "content, created_at) values (?, ?, 'user', 'x', now())", UUID.randomUUID(), UUID.randomUUID())))
                .isInstanceOf(DataAccessException.class);
        UUID session = inTenant(tenant, () -> chats.insertSession(doc, "ana").id());
        assertThatThrownBy(() -> inTenant(tenant, () -> jdbc.update("insert into chat_message (id, session_id, role, "
                + "content, created_at) values (?, ?, 'system', 'x', now())", UUID.randomUUID(), session)))
                .isInstanceOf(DataAccessException.class);
        // Cita a un chunk inexistente.
        assertThatThrownBy(() -> inTenant(tenant, () -> {
            chats.insertCitations(UUID.randomUUID(), List.of(new Citation(UUID.randomUUID(), 1, "q")));
            return null;
        })).isInstanceOf(DataAccessException.class);
    }

    @Test
    void historialYCitasSonInmutablesUpdateDeleteYTruncate() {
        UUID doc = UUID.randomUUID();
        UUID chunk = seedDocument(doc);
        UUID answer = seedAnswer(doc, chunk);

        for (String sql : List.of(
                "update chat_message set content = 'alterado'",
                "delete from chat_message",
                "truncate chat_message",
                "truncate chat_message cascade",
                "update citation set exact_quote = 'alterado'",
                "delete from citation",
                "truncate citation")) {
            // TRUNCATE sin CASCADE sobre chat_message ya lo impiden las FK; con CASCADE actua el trigger.
            assertThatThrownBy(() -> inTenant(tenant, () -> jdbc.update(sql))).as(sql)
                    .isInstanceOf(DataAccessException.class)
                    .hasMessageMatching("(?s).*(inmutables|cannot truncate a table referenced).*");
        }
        assertThat(count("select count(*) from chat_message")).isEqualTo(2);
        assertThat(count("select count(*) from citation where message_id = ?", answer)).isEqualTo(1);
        assertThat(count("select count(*) from chat_message where content = 'alterado'")).isZero();
    }

    @Test
    void busquedaPorDistanciaCosenoOrdenaPorSimilitudYSeAcotaAlDocumento() {
        UUID doc1 = UUID.randomUUID();
        UUID doc2 = UUID.randomUUID();
        UUID near = seedDocument(doc1);
        // El documento 2 contiene el vector identico a la consulta (similitud 1) pero no debe aparecer para doc1.
        seedDocument(doc2);

        var found = inTenant(tenant, () -> chunks.findSimilar(doc1, axis(0), 5, 0.0));

        assertThat(found).hasSize(2);
        assertThat(found.get(0).id()).isEqualTo(near);
        assertThat(found.get(0).similarity()).isEqualTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(found.get(1).similarity()).isEqualTo(0.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(found).allSatisfy(c -> assertThat(c.documentId()).isEqualTo(doc1));
        assertThat(inTenant(tenant, () -> chunks.findSimilar(doc1, axis(0), 5, 0.5))).hasSize(1);
        assertThat(inTenant(tenant, () -> chunks.findSimilar(doc1, axis(0), 1, 0.0))).hasSize(1);
        assertThat(inTenant(tenant, () -> chunks.findSimilar(UUID.randomUUID(), axis(0), 5, 0.0))).isEmpty();
    }

    @Test
    void insertIndexedEsAtomicoEIdempotente() {
        UUID doc = UUID.randomUUID();
        seedDocument(doc);

        boolean again = inTenant(tenant, () -> chunks.insertIndexed(doc,
                List.of(new NewChunk(UUID.randomUUID(), 0, 1, "otro", axis(2)))));

        assertThat(again).isFalse();
        assertThat(count("select count(*) from chunk where document_id = ?", doc)).isEqualTo(2);
        assertThat(count("select count(*) from chunk where content = 'otro'")).isZero();
    }

    @Test
    void siUnChunkFallaSeRevierteElEstadoDeIndexacion() {
        UUID doc = UUID.randomUUID();
        UUID dup = UUID.randomUUID();
        var tx = new org.springframework.transaction.support.TransactionTemplate(
                new org.springframework.jdbc.datasource.DataSourceTransactionManager(jdbc.getDataSource()));

        assertThatThrownBy(() -> inTenant(tenant, () -> tx.execute(s -> chunks.insertIndexed(doc,
                List.of(new NewChunk(dup, 0, 1, "a", axis(0)), new NewChunk(dup, 1, 1, "b", axis(1)))))))
                .isInstanceOf(DataAccessException.class);

        assertThat(count("select count(*) from document_index_status where document_id = ?", doc)).isZero();
        assertThat(count("select count(*) from chunk where document_id = ?", doc)).isZero();
    }
}
