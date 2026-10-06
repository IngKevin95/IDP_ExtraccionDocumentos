package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.review.domain.FieldCandidate;
import com.idp.review.infra.JdbcExtractionFieldSource;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

/** Los campos dudosos salen de field_value (requires_review) del extraction-service en el silo del tenant. */
class JdbcExtractionFieldSourceTest {

    private JdbcTemplate jdbc;
    private DataSourceTransactionManager tx;
    private JdbcExtractionFieldSource source;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource ds = new DriverManagerDataSource(
                "jdbc:h2:mem:fields-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(ds);
        tx = new DataSourceTransactionManager(ds);
        source = new JdbcExtractionFieldSource(jdbc, tx);
    }

    private void createExtractionTables() {
        jdbc.execute("create table extraction (id uuid primary key, document_id uuid not null, "
                + "review_task_id uuid)");
        jdbc.execute("create table field_value (id uuid primary key, extraction_id uuid not null, "
                + "table_name varchar(64), row_index integer, field_name varchar(64) not null, value_text text, "
                + "confidence_score numeric(5,4), evidence_page integer, bounding_box varchar(200), "
                + "requires_review boolean not null default false)");
    }

    private void field(UUID extraction, String table, Integer row, String name, boolean review, Integer page,
                       String bbox) {
        jdbc.update("insert into field_value (id, extraction_id, table_name, row_index, field_name, value_text, "
                + "confidence_score, evidence_page, bounding_box, requires_review) values (?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID(), extraction, table, row, name, "v-" + name, 0.5, page, bbox, review);
    }

    @Test
    void devuelveSoloLosCamposConRequiresReviewDeLaTareaConSuEvidencia() {
        createExtractionTables();
        UUID doc = UUID.randomUUID();
        UUID task = UUID.randomUUID();
        UUID extraction = UUID.randomUUID();
        UUID otherExtraction = UUID.randomUUID();
        jdbc.update("insert into extraction values (?,?,?)", extraction, doc, task);
        jdbc.update("insert into extraction values (?,?,?)", otherExtraction, UUID.randomUUID(), UUID.randomUUID());
        field(extraction, null, null, "monto_numeros", true, 2, "[0.1,0.2,0.3,0.1]");
        field(extraction, null, null, "ciudad", false, 1, "[0,0,1,1]");
        field(extraction, "demandados", 1, "monto", true, null, null);
        field(otherExtraction, null, null, "direccion", true, 1, "[0,0,1,1]");

        List<FieldCandidate> found = new TransactionTemplate(tx).execute(s -> source.candidates(doc, task));

        assertThat(found).extracting(FieldCandidate::fieldName).containsExactlyInAnyOrder("monto_numeros",
                "demandados[1].monto");
        FieldCandidate monto = found.stream().filter(c -> c.fieldName().equals("monto_numeros")).findFirst()
                .orElseThrow();
        assertThat(monto.page()).isEqualTo(2);
        assertThat(monto.boundingBox()).isEqualTo("[0.1,0.2,0.3,0.1]");
        assertThat(monto.originalValue()).isEqualTo("v-monto_numeros");
        FieldCandidate tabla = found.stream().filter(c -> c.fieldName().startsWith("demandados")).findFirst()
                .orElseThrow();
        assertThat(tabla.page()).isNull();
    }

    @Test
    void sinLasTablasDelExtractionServiceDevuelveVacioSinAbortarLaTransaccionDelLlamador() {
        List<FieldCandidate> found = new TransactionTemplate(tx).execute(s -> {
            List<FieldCandidate> r = source.candidates(UUID.randomUUID(), UUID.randomUUID());
            // la transaccion externa sigue usable tras el fallo aislado
            assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
            return r;
        });

        assertThat(found).isEmpty();
    }

    @Test
    void camposDeLaUltimaExtraccionDelDocumentoYSuCargadorParaLaRevisionCiega() {
        jdbc.execute("create table extraction (id uuid primary key, document_id uuid not null, "
                + "review_task_id uuid, created_at timestamp with time zone not null)");
        jdbc.execute("create table field_value (id uuid primary key, extraction_id uuid not null, "
                + "table_name varchar(64), row_index integer, field_name varchar(64) not null, value_text text, "
                + "confidence_score numeric(5,4), evidence_page integer, bounding_box varchar(200), "
                + "requires_review boolean not null default false)");
        jdbc.execute("create table document (id uuid primary key, uploaded_by varchar(128))");
        UUID doc = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID latest = UUID.randomUUID();
        jdbc.update("insert into extraction values (?,?,?, timestamp with time zone '2026-01-01 10:00:00+00')", old,
                doc, null);
        jdbc.update("insert into extraction values (?,?,?, timestamp with time zone '2026-01-02 10:00:00+00')",
                latest, doc, null);
        field(old, null, null, "monto_viejo", false, 1, "[0,0,1,1]");
        field(latest, null, null, "monto", false, 1, "[0.1,0.2,0.3,0.1]");
        field(latest, null, null, "ciudad", false, 2, "[0,0,1,1]");
        jdbc.update("insert into document values (?, ?)", doc, "operador-1");

        List<FieldCandidate> found = new TransactionTemplate(tx).execute(s -> source.approvedFields(doc));
        java.util.Optional<String> uploader = new TransactionTemplate(tx).execute(s -> source.uploader(doc));

        assertThat(found).extracting(FieldCandidate::fieldName).containsExactlyInAnyOrder("monto", "ciudad");
        assertThat(uploader).contains("operador-1");
        assertThat(source.approvedFields(UUID.randomUUID())).isEmpty();
    }

    @Test
    void estadoDeAprobacionDelDocumentoSeLeeDelSiloYSinLaTablaNoEsVerificable() {
        UUID doc = UUID.randomUUID();
        assertThat(source.approvedDocument(doc).isPresent()).isFalse();
        jdbc.execute("create table document (id uuid primary key, uploaded_by varchar(128), "
                + "status varchar(32), approved_by varchar(32), classification varchar(32))");
        jdbc.update("insert into document values (?, 'op', 'APROBADO', 'AUTO_STP', 'CONFIDENCIAL')", doc);

        java.util.Optional<com.idp.review.infra.FieldCandidateSource.ApprovedDocument> found =
                new TransactionTemplate(tx).execute(s -> source.approvedDocument(doc));

        assertThat(found).contains(new com.idp.review.infra.FieldCandidateSource.ApprovedDocument("APROBADO",
                "AUTO_STP", "CONFIDENCIAL"));
        assertThat(source.approvedDocument(UUID.randomUUID())).isEmpty();
    }
}
