package com.idp.chat;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.events.EventValidationException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** AC-01 e indexacion segura: SEC-007 (indexar), SEC-050, SEC-052, idempotencia y aislamiento por tenant. */
class IndexingIntegrationTest extends AbstractChatIntegrationTest {

    static String longPage(String sentence, int times) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < times; i++) {
            sb.append(sentence).append(" Parte ").append(i).append('.');
            sb.append(i % 5 == 4 ? "\n\n" : " ");
        }
        return sb.toString();
    }

    @Test
    void ac01_extraeTextoGeneraChunksConEmbeddingsLosGuardaYMarcaIndexado() {
        String p1 = longPage("El juzgado ordena el embargo por un monto de 1500000 pesos.", 40);
        String p2 = "La cuenta del titular queda embargada con vigencia de un ano.";

        UUID doc = indexedDocument(p1, p2);

        int chunks = count("select count(*) from chunk where document_id = ?", doc);
        assertThat(chunks).isGreaterThan(3);
        assertThat(count("select count(*) from document_index_status where document_id = ? and status = 'INDEXED'",
                doc)).isEqualTo(1);
        assertThat(count("select count(*) from chunk where document_id = ? and page_number = 2", doc)).isEqualTo(1);
        assertThat(count("select count(*) from chunk where document_id = ? and page_number = 1", doc))
                .isEqualTo(chunks - 1);
        assertThat(count("select count(*) from chunk where document_id = ? and length(content) > 1000", doc)).isZero();
        assertThat(count("select count(distinct ordinal) from chunk where document_id = ?", doc)).isEqualTo(chunks);
        assertThat(count("select count(*) from chunk where document_id = ? and vector_dims(embedding) = 8", doc))
                .isEqualTo(chunks);
        // Cada fragmento es una subcadena literal de su pagina (las citas se validan contra ese contenido).
        List<String> contents = inTenant(tenant, () -> jdbc.queryForList(
                "select content from chunk where document_id = ? and page_number = 1", String.class, doc));
        assertThat(contents).allSatisfy(c -> assertThat(p1).contains(c));
        assertThat(count("select count(*) from processed_event")).isEqualTo(1);
    }

    @Test
    void reentregaDeExtraccionAprobadaEsIdempotente() {
        UUID doc = UUID.randomUUID();
        registerDocument(tenant, doc, "CONFIDENCIAL", "u");
        putTextLayer(tenant, doc, longPage("El monto del embargo es 100 pesos.", 30));
        UUID eventId = UUID.randomUUID();
        String event = aprobada(tenant, eventId, doc);

        com.idp.testsupport.Topics.deliver(listener::onMessage, event);
        int after = count("select count(*) from chunk where document_id = ?", doc);
        int calls = embeddings.batchCalls();

        // Misma entrega repetida (mismo eventId) y otra entrega del mismo documento con otro eventId.
        com.idp.testsupport.Topics.deliver(listener::onMessage, event);
        approve(tenant, doc);

        assertThat(count("select count(*) from chunk where document_id = ?", doc)).isEqualTo(after);
        assertThat(count("select count(*) from document_index_status where document_id = ?", doc)).isEqualTo(1);
        // El segundo eventId ni siquiera recalcula embeddings: el documento ya esta indexado.
        assertThat(embeddings.batchCalls()).isEqualTo(calls);
    }

    @Test
    void sec007_documentoAusenteDelTenantNoSeIndexaYEmiteAccesoDenegado() {
        UUID ghost = UUID.randomUUID();
        putTextLayer(tenant, ghost, "El monto del embargo es 100 pesos.");

        assertThatThrownBy(() -> approve(tenant, ghost)).isInstanceOf(EventValidationException.class);

        assertThat(count("select count(*) from chunk")).isZero();
        assertThat(count("select count(*) from document_index_status")).isZero();
        var denied = outbox("seguridad.acceso_denegado");
        assertThat(denied).hasSize(1);
        assertThat(denied.get(0).path("resourceId").asText()).isEqualTo(ghost.toString());
        assertThat(denied.get(0).path("reasonCode").asText()).isEqualTo("UNAUTHORIZED_TENANT");
        assertEventsClean();
    }

    @Test
    void sec007_documentoPurgadoNoSeIndexa() {
        UUID doc = UUID.randomUUID();
        registerDocument(tenant, doc, "CONFIDENCIAL", "u");
        inTenant(tenant, () -> jdbc.update("update document set purged_at = now() where id = ?", doc));
        putTextLayer(tenant, doc, "El monto del embargo es 100 pesos.");

        assertThatThrownBy(() -> approve(tenant, doc)).isInstanceOf(EventValidationException.class);
        assertThat(count("select count(*) from chunk")).isZero();
    }

    @Test
    void capaDeTextoAusenteFallaParaReintentoYDltSinMarcarIndexado() {
        UUID doc = UUID.randomUUID();
        registerDocument(tenant, doc, "CONFIDENCIAL", "u");

        assertThatThrownBy(() -> approve(tenant, doc)).isInstanceOf(IllegalStateException.class);

        assertThat(count("select count(*) from document_index_status")).isZero();
        assertThat(count("select count(*) from processed_event")).isZero();
    }

    @Test
    void documentoSinTextoEsErrorFatal() {
        UUID doc = UUID.randomUUID();
        registerDocument(tenant, doc, "CONFIDENCIAL", "u");
        putTextLayer(tenant, doc, "   ", "");

        assertThatThrownBy(() -> approve(tenant, doc)).isInstanceOf(EventValidationException.class);
    }

    @Test
    void sec052_eventosFueraDeContratoOTopicoIncorrectoNoIndexan() {
        UUID doc = UUID.randomUUID();
        registerDocument(tenant, doc, "CONFIDENCIAL", "u");
        putTextLayer(tenant, doc, "El monto del embargo es 100 pesos.");
        String ok = aprobada(tenant, UUID.randomUUID(), doc);

        // Topico de origen incorrecto: se ignora sin error (alerta SECURITY).
        assertThatCode(() -> listener.onMessage(ok, "review.events")).doesNotThrowAnyException();
        assertThat(count("select count(*) from chunk")).isZero();

        // Poison pills: JSON roto, campos faltantes, documentId no UUID. Todos son EventValidationException (DLT).
        assertThatThrownBy(() -> listener.onMessage("{no es json", "document.events"))
                .isInstanceOf(EventValidationException.class);
        assertThatThrownBy(() -> listener.onMessage(ok.replace(doc.toString(), "no-es-uuid"), "document.events"))
                .isInstanceOf(EventValidationException.class);
        assertThatThrownBy(() -> listener.onMessage(ok.replace("\"approvedBy\":\"AUTO_STP\"", "\"approvedBy\":\"X\""),
                "document.events")).isInstanceOf(EventValidationException.class);
        assertThat(count("select count(*) from chunk")).isZero();
    }

    @Test
    void rnf101_elEventoIndexaSoloEnElSiloDeSuTenantYUnDocumentoAjenoSeRechaza() {
        String other = newTenant();
        UUID doc = indexedDocument(longPage("El monto del embargo es 100 pesos.", 10));
        assertThat(count("select count(*) from chunk where document_id = ?", doc)).isPositive();
        assertThat(inTenant(other, () -> jdbc.queryForObject("select count(*) from chunk", Integer.class))).isZero();

        // El mismo documentId anunciado por el tenant B no existe en el silo de B: rechazo y senal en B.
        assertThatThrownBy(() -> approve(other, doc)).isInstanceOf(EventValidationException.class);

        assertThat(inTenant(other, () -> jdbc.queryForObject("select count(*) from chunk", Integer.class))).isZero();
        assertThat(inTenant(other, () -> jdbc.queryForObject(
                "select count(*) from outbox where event_type = 'seguridad.acceso_denegado'", Integer.class)))
                .isEqualTo(1);
    }
}
