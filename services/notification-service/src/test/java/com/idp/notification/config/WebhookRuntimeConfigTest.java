package com.idp.notification.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.notification.net.AddressPolicy;
import java.time.Duration;
import org.junit.jupiter.api.Test;

/** El http plano solo puede habilitarse en dev-mode: la configuracion insegura no arranca en produccion. */
class WebhookRuntimeConfigTest {

    private static NotificationProperties props(boolean insecure) {
        return new NotificationProperties(5, Duration.ofSeconds(30), 2.0, Duration.ofHours(1), Duration.ofDays(7),
            insecure, new NotificationProperties.Http(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10)),
            new NotificationProperties.Worker(true, Duration.ofSeconds(1), 10, Duration.ofMinutes(5)));
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
    void laPoliticaDeProduccionEsLaEstricta() {
        assertThat(new WebhookRuntimeConfig().addressPolicy()).isSameAs(AddressPolicy.STRICT);
    }
}
