package com.idp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FactTokensTest {

    @Test
    void montosEnDistintosFormatosSeNormalizan() {
        assertThat(FactTokens.extract("$1.500.000,00")).containsExactly("N:1500000");
        assertThat(FactTokens.extract("$1,500,000.00")).containsExactly("N:1500000");
        assertThat(FactTokens.extract("1500000")).containsExactly("N:1500000");
        assertThat(FactTokens.extract("12,5 %")).containsExactly("N:12.5");
        assertThat(FactTokens.extract("0,500")).containsExactly("N:0.5");
    }

    @Test
    void montosEnLetras() {
        assertThat(FactTokens.extract("un millon quinientos mil pesos")).containsExactly("N:1500000");
        assertThat(FactTokens.extract("dos mil millones")).containsExactly("N:2000000000");
        assertThat(FactTokens.extract("treinta y cinco")).containsExactly("N:35");
        assertThat(FactTokens.extract("veintiun mil")).containsExactly("N:21000");
        assertThat(FactTokens.extract("ciento veinte")).containsExactly("N:120");
        assertThat(FactTokens.extract("un oficio y una cuenta")).isEmpty();
    }

    @Test
    void fechasEnDistintosFormatos() {
        assertThat(FactTokens.extract("15 de marzo de 2024")).containsExactly("D:2024-03-15");
        assertThat(FactTokens.extract("15/03/2024")).containsExactly("D:2024-03-15");
        assertThat(FactTokens.extract("2024-03-15")).containsExactly("D:2024-03-15");
        assertThat(FactTokens.extract("marzo 15 de 2024")).containsExactly("D:2024-03-15");
        assertThat(FactTokens.extract("15 de marzo")).containsExactly("D:--03-15");
        assertThat(FactTokens.extract("31/02/2024")).doesNotContain("D:2024-02-31");
    }

    @Test
    void monedaExtranjera() {
        assertThat(FactTokens.extract("500 dolares")).contains("C:USD", "N:500");
        assertThat(FactTokens.extract("EUR 20")).contains("C:EUR", "N:20");
    }
}
