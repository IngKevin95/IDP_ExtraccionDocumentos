package com.idp.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.idp.review.config.ReviewProperties;
import com.idp.review.service.CriticalFields;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** RF-302: campos criticos (monto, identificacion, cuenta, producto, tipo de medida). */
class CriticalFieldsTest {

    static ReviewProperties props(List<String> critical) {
        return new ReviewProperties("documents", "http://x", "s".repeat(32), Duration.ofSeconds(60),
                Duration.ofHours(4), Duration.ofHours(1), 3, critical, "none", false, 1024, 2, 30, 40_000_000L,
                new ReviewProperties.Relay(false, Duration.ofSeconds(1)),
                new ReviewProperties.Escalation(false, Duration.ofMinutes(1)));
    }

    private final CriticalFields critical = new CriticalFields(
            props(List.of("monto", "identificacion", "cuenta", "producto", "tipo_medida", "radicado")));

    @ParameterizedTest
    @ValueSource(strings = {"monto", "MONTO", "monto_numeros", "numero_identificacion", "productos_o_cuentas",
            "tipo_medida", "radicado", "demandados[0].monto", "demandados[2].productos", " Cuenta "})
    void sonCriticos(String field) {
        assertThat(critical.isCritical(field)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"direccion", "ciudad", "fecha_oficio", "autoridad_emisora", "juzgado", "numero_resolucion",
            "demandados[0].nombre"})
    void noSonCriticos(String field) {
        assertThat(critical.isCritical(field)).isFalse();
    }

    @Test
    void nombreNuloOVacioNoEsCritico() {
        assertThat(critical.isCritical(null)).isFalse();
        assertThat(critical.isCritical("")).isFalse();
    }

    @Test
    void laListaEsConfigurableYIgnoraFragmentosVacios() {
        CriticalFields only = new CriticalFields(props(List.of("ciudad", " ")));

        assertThat(only.isCritical("ciudad")).isTrue();
        assertThat(only.isCritical("monto")).isFalse();
        assertThat(only.isCritical("direccion")).isFalse();
    }
}
