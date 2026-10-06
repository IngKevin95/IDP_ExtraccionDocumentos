package com.idp.chat.infra;

import com.idp.chat.domain.Chunk;
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
 * {@code document_id}: la busqueda esta acotada al documento de la sesion (RF-401, AC-06).
 */
@Repository
public class ChunkRepository {

    /** Fragmento a insertar con su vector. */
    public record NewChunk(UUID id, int ordinal, int pageNumber, String content, float[] embedding) {
    }

    private static final RowMapper<Chunk> CHUNK = (rs, i) -> new Chunk(rs.getObject("id", UUID.class),
            rs.getObject("document_id", UUID.class), rs.getInt("ordinal"), rs.getInt("page_number"),
            rs.getString("content"), rs.getDouble("similarity"));

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
        jdbc.batchUpdate("insert into chunk (id, document_id, ordinal, page_number, content, embedding) "
                + "values (?, ?, ?, ?, ?, cast(? as vector))", chunks, 100,
                (PreparedStatement ps, NewChunk c) -> bind(ps, documentId, c));
        return true;
    }

    private static void bind(PreparedStatement ps, UUID documentId, NewChunk c) throws SQLException {
        ps.setObject(1, c.id());
        ps.setObject(2, documentId);
        ps.setInt(3, c.ordinal());
        ps.setInt(4, c.pageNumber());
        ps.setString(5, c.content());
        ps.setString(6, Vectors.literal(c.embedding()));
    }

    /** Vecinos mas cercanos del documento por distancia coseno, con similitud >= minSimilarity (1 - distancia). */
    public List<Chunk> findSimilar(UUID documentId, float[] query, int topK, double minSimilarity) {
        String vector = Vectors.literal(query);
        return jdbc.query("select * from (select id, document_id, ordinal, page_number, content, "
                + "1 - (embedding <=> cast(? as vector)) as similarity from chunk where document_id = ? "
                + "order by embedding <=> cast(? as vector) limit ?) r where r.similarity >= ? "
                + "order by r.similarity desc", CHUNK, vector, documentId, vector, topK, minSimilarity);
    }
}
