package com.idp.notification.net;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/** Logica pura del verificador DNS (sin red): candidatos de consulta y comparacion del TXT. */
class DnsDomainOwnershipVerifierTest {

    @Test
    void consultaElDominioYSusAncestrosSinLlegarAlSufijoPublico() {
        assertThat(DnsDomainOwnershipVerifier.candidates("a.b.banco.com.co"))
            .containsExactly("a.b.banco.com.co", "b.banco.com.co", "banco.com.co");
        assertThat(DnsDomainOwnershipVerifier.candidates("banco.com")).containsExactly("banco.com");
        assertThat(DnsDomainOwnershipVerifier.candidates("com")).isEmpty();
    }

    @Test
    void elTxtDebeCoincidirExactamenteConElValorDelToken() {
        String expected = DomainOwnershipVerifier.challengeValue("tok123");
        assertThat(DnsDomainOwnershipVerifier.matches(List.of("\"idp-verify=tok123\""), expected)).isTrue();
        assertThat(DnsDomainOwnershipVerifier.matches(List.of("idp-verify=tok123"), expected)).isTrue();
        assertThat(DnsDomainOwnershipVerifier.matches(List.of("v=spf1 -all", "idp-verify=tok123"), expected)).isTrue();
        assertThat(DnsDomainOwnershipVerifier.matches(List.of("idp-verify=tok1234"), expected)).isFalse();
        assertThat(DnsDomainOwnershipVerifier.matches(List.of("xidp-verify=tok123"), expected)).isFalse();
        assertThat(DnsDomainOwnershipVerifier.matches(List.of(), expected)).isFalse();
        assertThat(DomainOwnershipVerifier.challengeName("banco.com")).isEqualTo("_idp-verify.banco.com");
    }
}
