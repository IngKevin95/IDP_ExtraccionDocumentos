package com.idp.notification.event;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

/** Hallazgo 7: el reasonCode libre de otro servicio no se copia al webhook salvo que sea un codigo cerrado. */
class NotificationEventHandlerTest {

    @ParameterizedTest
    @ValueSource(strings = {"INVALID_MAGIC_BYTES", "FILE_SIZE_EXCEEDED", "A", "X_Y_Z"})
    void codigosCerradosSeCopian(String code) {
        assertThat(NotificationEventHandler.safeReasonCode(code)).isEqualTo(code);
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "invalid_magic", "CON ESPACIO", "<script>alert(1)</script>", "CODE1", "CODE\n",
        "A_VERY_LONG_REASON_CODE_THAT_EXCEEDS_FORTY_CHARS", "https://evil.test/x", "MOTIVO-CON-GUION"})
    void cualquierOtroValorSeReemplazaPorUnspecified(String code) {
        assertThat(NotificationEventHandler.safeReasonCode(code)).isEqualTo("UNSPECIFIED");
    }
}
