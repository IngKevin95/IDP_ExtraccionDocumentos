package com.idp.notification.net;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SEC-027 (hallazgo 1): comodines sobre sufijos publicos y de una sola etiqueta se rechazan. */
class PublicSuffixesTest {

    @ParameterizedTest
    @ValueSource(strings = {"com", "co", "test", "co.uk", "com.ar", "com.co", "gob.pe", "org.mx", "github.io",
        "herokuapp.com", "amazonaws.com", "vercel.app", "COM", "com."})
    void sufijosPublicosYEtiquetasUnicasSeDetectan(String domain) {
        assertThat(PublicSuffixes.isPublicSuffix(domain)).as(domain).isTrue();
        assertThat(PublicSuffixes.wildcardBaseAllowed(domain)).as("*." + domain).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"banco.com", "banco.com.co", "partner.co", "api.banco.com", "banco.co.uk", "hook.banco.test",
        "empresa.com.ar"})
    void dominiosRegistrablesNoSonSufijosPublicos(String domain) {
        assertThat(PublicSuffixes.isPublicSuffix(domain)).as(domain).isFalse();
        assertThat(PublicSuffixes.wildcardBaseAllowed(domain)).as("*." + domain).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"us-east-1.amazonaws.com", "miapp.herokuapp.com", "usuario.github.io"})
    void comodinSobreZonasCompartidasDeProveedorSeRechaza(String base) {
        assertThat(PublicSuffixes.wildcardBaseAllowed(base)).isFalse();
    }
}
