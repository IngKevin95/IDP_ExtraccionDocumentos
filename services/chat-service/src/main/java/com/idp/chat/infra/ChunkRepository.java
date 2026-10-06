package com.idp.chat.infra;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * Indice vectorial del silo del tenant actual (el DataSource enrutado lo resuelve por contexto). Toda consulta lleva
 * {@code document_id}: la busqueda esta acotada al documento de la sesion (RF-401, AC-06). El texto del fragmento se
 * guarda cifrado (bytea); el embedding no.
 */
@Repository
public class ChunkRepository {

    /** Fragmento a insertar con su texto ya cifrado y su vector. */
    public record NewChunk(UUID id, int ordinal, int pageNumber, byte[] contentEnc, float[] embedding) {
    }

    /** Fragmento recuperado por similitud, con el texto aun cifrado. */
    public record Found(UUID id, UUID documentId, int ordinal, int pageNumber, byte[] contentEnc, double similarity) {
    }

    private static final RowMapper<Found> FOUND = (rs, i) -> new Found(rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class), rs.getInt("ordinal"), rs.getInt("page_number"),
            rs.getBytes("content_enc"), rs.getDouble("similarity"));

    private final JdbcTemplate jdbc;

    public ChunkRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public boolean isIndexed(UUID documentId) {
        Integer n = jdbc.queryForObject("select count(*) from document_index_status where document_id = ?",
                Integer.class, documentId);
        return n != null && n > 0;
    }

    /**
     * Marca el documento como indexado e inserta sus fragmentos. Devuelve false (sin insertar nada) si otro consumidor ya
     * lo indexo: la entrega repetida de extraccion.aprobada es inocua. Debe ejecutarse en una sola transaccion.
     */
    public boolean insertIndexed(UUID documentId, List<NewChunk> chunks) {
        int claimed = jdbc.update("insert into document_index_status (document_id, status, indexed_at) "
                + "values (?, 'INDEXED', ?) on conflict (document_id) do nothing", documentId,
                OffsetDateTime.now(ZoneOffset.UTC));
        if (claimed == 0) {
            return false;
        }
        jdbc.batchUpdate("insert into chunk (id, document_id, ordinal, page_number, content_enc, embedding) "
                + "values (?, ?, ?, ?, ?, cast(? as vector))", chunks, 100,
                (PreparedStatement ps, NewChunk c) -> bind(ps, documentId, c));
        return true;
    }

    private static void bind(PreparedStatement ps, UUID documentId, NewChunk c) throws SQLException {
        ps.setObject(1, c.id());
        ps.setObject(2, documentId);
        ps.setInt(3, c.ordinal());
        ps.setInt(4, c.pageNumber());
        ps.setBytes(5, c.contentEnc());
        ps.setString(6, Vectors.literal(c.embedding()));
    }

    /** Vecinos mas cercanos del documento por distancia coseno, con similitud >= minSimilarity (1 - distancia). */
    public List<Found> findSimilar(UUID documentId, float[] query, int topK, double minSimilarity) {
        String vector = Vectors.literal(query);
        return jdbc.query("select * from (select id, document_id, ordinal, page_number, content_enc, "
                + "1 - (embedding <=> cast(? as vector)) as similarity from chunk where document_id = ? "
                + "and content_enc is not null order by embedding <=> cast(? as vector) limit ?) r "
                + "where r.similarity >= ? order by r.similarity desc", FOUND, vector, documentId, vector, topK,
                minSimilarity);
    }
}
