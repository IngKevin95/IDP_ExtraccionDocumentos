package com.idp.synthetic;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.extraction.validation.CedulaValidator;
import com.idp.extraction.validation.NitValidator;
import com.idp.extraction.validation.SpanishNumberWords;
import com.idp.extraction.validation.ValidationContext;
import java.time.LocalDate;
import java.util.Random;
import org.junit.jupiter.api.Test;

class NumerosEIdentificacionesTest {

    private static final ValidationContext CTX = ValidationContext.of(java.util.Map.of(), LocalDate.of(2026, 6, 30));

    @Test
    void numerosEnLetrasConocidos() {
        assertThat(NumberWords.palabras(0)).isEqualTo("cero");
        assertThat(NumberWords.palabras(21_000)).isEqualTo("veintiún mil");
        assertThat(NumberWords.palabras(1_000_000)).isEqualTo("un millón");
        assertThat(NumberWords.palabras(101_000)).isEqualTo("ciento un mil");
        assertThat(NumberWords.pesos(200_000_000)).isEqualTo("DOSCIENTOS MILLONES DE PESOS M/CTE");
        assertThat(NumberWords.pesos(12_345_678))
            .isEqualTo("DOCE MILLONES TRESCIENTOS CUARENTA Y CINCO MIL SEISCIENTOS SETENTA Y OCHO PESOS M/CTE");
        assertThatThrownBy(() -> NumberWords.palabras(-1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void letrasSonInterpretablesPorElValidadorDeExtraccion() {
        long[] bordes = {0, 1, 15, 16, 21, 29, 30, 31, 99, 100, 101, 999, 1000, 1001, 21_000, 100_000, 999_999,
            1_000_000, 1_000_001, 21_000_000, 999_000_000, 1_000_000_000, 123_456_789_012L};
        for (long n : bordes) {
            assertThat(SpanishNumberWords.parse(NumberWords.pesos(n))).as("n=%d", n).contains(n);
        }
        Random rng = new Random(11);
        for (int i = 0; i < 3000; i++) {
            long n = (long) (rng.nextDouble() * Math.pow(10, rng.nextInt(12)));
            assertThat(SpanishNumberWords.parse(NumberWords.pesos(n))).as("n=%d", n).contains(n);
        }
    }

    @Test
    void nitFicticioTieneDigitoModulo11Correcto() {
        assertThat(Identificaciones.digitoVerificacion("900123456")).isEqualTo(NitValidator.checkDigit("900123456"));
        Random rng = new Random(5);
        NitValidator nit = new NitValidator();
        for (int i = 0; i < 2000; i++) {
            String valor = Identificaciones.nit(rng);
            assertThat(valor).matches("999\\d{6}-\\d");
            assertThat(nit.validate(valor, CTX).passed()).as(valor).isTrue();
        }
    }

    @Test
    void cedulaFicticiaTieneFormatoValido() {
        Random rng = new Random(6);
        CedulaValidator cedula = new CedulaValidator();
        for (int i = 0; i < 2000; i++) {
            String valor = Identificaciones.cedula(rng);
            assertThat(cedula.validate(valor, CTX).passed()).as(valor).isTrue();
            // Rango reservado: 10 digitos que empiezan por 99, nunca asignado a una persona.
            assertThat(valor).matches("99\\d{8}");
        }
    }
}
