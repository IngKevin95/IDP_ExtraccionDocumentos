package com.idp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.chat.domain.Chunk;
import com.idp.chat.domain.Citation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SEC-031: las citas se validan contra los fragmentos entregados al modelo. */
class GroundingVerifierTest {

    private final GroundingVerifier verifier = new GroundingVerifier();
    private final UUID id = UUID.randomUUID();
    private final Chunk chunk = new Chunk(id, UUID.randomUUID(), 0, 3,
            "El juzgado ordena el embargo\npor un monto de $1,500,000.00 contra el titular.", 0.9);

    @Test
    void aceptaCitaLiteralYLaSustituyePorNumero() {
        var r = verifier.verify("Monto embargado. [chunk:" + id + "] \"monto de $1,500,000.00\" Fin.", List.of(chunk));

        assertThat(r.invalid()).isZero();
        assertThat(r.citations()).containsExactly(new Citation(id, 3, "monto de $1,500,000.00"));
        assertThat(r.text()).isEqualTo("Monto embargado. [1] Fin.");
    }

    @Test
    void toleraDiferenciasDeEspaciosEnBlancoYGuardaLaCitaNormalizada() {
        var r = verifier.verify("[chunk:" + id + "] \"embargo por un monto\"", List.of(chunk));

        assertThat(r.invalid()).isZero();
        assertThat(r.citations()).containsExactly(new Citation(id, 3, "embargo por un monto"));
    }

    @Test
    void aceptaComillasTipograficasYEscapadas() {
        assertThat(verifier.verify("[chunk:" + id + "] “el titular”", List.of(chunk)).citations()).hasSize(1);
        Chunk quoted = new Chunk(id, UUID.randomUUID(), 0, 1, "dijo \"si acepto\" ante el juez", 1);
        assertThat(verifier.verify("[chunk:" + id + "] \"dijo \\\"si acepto\\\" ante\"", List.of(quoted)).citations())
                .hasSize(1);
    }

    @Test
    void rechazaTextoInventadoChunkAjenoCitaCortaYMarcadoresMalFormados() {
        UUID other = UUID.randomUUID();
        assertThat(verifier.verify("[chunk:" + id + "] \"monto de 9 pesos inventado\"", List.of(chunk)).invalid())
                .isEqualTo(1);
        assertThat(verifier.verify("[chunk:" + other + "] \"el titular\"", List.of(chunk)).invalid()).isEqualTo(1);
        assertThat(verifier.verify("[chunk:" + id + "] \"El\"", List.of(chunk)).invalid()).isEqualTo(1);
        assertThat(verifier.verify("[chunk:xyz] \"el titular\"", List.of(chunk)).invalid()).isEqualTo(1);
        assertThat(verifier.verify("[chunk:" + id + "] sin comillas", List.of(chunk)).invalid()).isEqualTo(1);
    }

    @Test
    void respuestaSinCitasYDeduplicacion() {
        var none = verifier.verify("El monto es 5.", List.of(chunk));
        assertThat(none.citations()).isEmpty();
        assertThat(none.invalid()).isZero();
        assertThat(none.abstention()).isFalse();

        var dup = verifier.verify("A [chunk:" + id + "] \"el titular\" B [chunk:" + id + "] \"el titular\"",
                List.of(chunk));
        assertThat(dup.citations()).hasSize(1);
        assertThat(dup.text()).isEqualTo("A [1] B [1]");
    }

    @Test
    void detectaAbstencionConYSinAcentos() {
        assertThat(verifier.verify("Información insuficiente", List.of(chunk)).abstention()).isTrue();
        assertThat(verifier.verify("INFORMACION INSUFICIENTE.", List.of(chunk)).abstention()).isTrue();
        assertThat(verifier.verify("El monto es 5", List.of(chunk)).abstention()).isFalse();
    }
}
