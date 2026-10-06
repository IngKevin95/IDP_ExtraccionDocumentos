package com.idp.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.notification.net.AddressPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** El http plano solo puede habilitarse en dev-mode: la configuracion insegura no arranca en produccion. */
class WebhookRuntimeConfigTest {

    private static NotificationProperties props(boolean insecure) {
        return props(insecure, NotificationProperties.HostVerification.DNS);
    }

    private static NotificationProperties props(boolean insecure, NotificationProperties.HostVerification mode) {
        return new NotificationProperties(5, Duration.ofSeconds(30), 2.0, Duration.ofHours(1), Duration.ofDays(7),
            insecure, new NotificationProperties.Http(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10)),
            new NotificationProperties.Worker(true, Duration.ofSeconds(1), 10, Duration.ofMinutes(5), 8, 16, 2, 2,
                Duration.ofSeconds(20)),
            mode, null, null, 3, new NotificationProperties.Breaker(50f, 10, 5, Duration.ofSeconds(60), 2));
    }

    @Test
    void hostVerificationNoneSoloSeAdmiteEnDevMode() {
        WebhookRuntimeConfig config = new WebhookRuntimeConfig();
        NotificationProperties none = props(false, NotificationProperties.HostVerification.NONE);
        assertThatThrownBy(() -> config.webhookUrlPolicy(AddressPolicy.STRICT, none, false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("host-verification");
        assertThat(config.webhookUrlPolicy(AddressPolicy.STRICT, none, true)).isNotNull();
    }

    @Test
    void rechazaHttpPlanoFueraDeDevMode() {
        WebhookRuntimeConfig config = new WebhookRuntimeConfig();
        assertThatThrownBy(() -> config.webhookUrlPolicy(AddressPolicy.STRICT, props(true), false))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("dev-mode");
        assertThat(config.webhookUrlPolicy(AddressPolicy.STRICT, props(true), true)).isNotNull();
        assertThat(config.webhookUrlPolicy(AddressPolicy.STRICT, props(false), false)).isNotNull();
    }

    @Test
    void laPoliticaDeProduccionSoloAdmiteElPuertoDe443YLosDeLaListaDePlataforma() {
        WebhookRuntimeConfig config = new WebhookRuntimeConfig();
        var prod = config.webhookUrlPolicy(AddressPolicy.STRICT, props(false), false);
        java.util.List<String> allow = java.util.List.of("api.banco.com");
        assertThat(prod.validate("https://api.banco.com/x", allow)).isNotNull();
        assertThatThrownBy(() -> prod.validate("https://api.banco.com:6379/", allow))
            .isInstanceOf(com.idp.notification.net.SsrfViolationException.class).hasMessageContaining("Puerto");
    }

    @Test
    void laPoliticaDeProduccionEsLaEstricta() {
        assertThat(new WebhookRuntimeConfig().addressPolicy()).isSameAs(AddressPolicy.STRICT);
    }
}
