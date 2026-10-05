package com.idp.notification.config;

import com.idp.kms.EnvelopeCrypto;
import com.idp.notification.http.ApacheWebhookTransport;
import com.idp.notification.http.HmacSignatureService;
import com.idp.notification.http.WebhookDispatcher;
import com.idp.notification.http.WebhookTransport;
import com.idp.notification.net.AddressPolicy;
import com.idp.notification.net.HostResolver;
import com.idp.notification.net.SsrfGuard;
import com.idp.notification.net.WebhookUrlPolicy;
import com.idp.notification.service.WebhookSecrets;
import com.idp.notification.store.WebhookRepository;
import com.idp.tenant.context.TenantKeyResolver;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/** Cableado del despacho saliente: politica anti-SSRF, firma HMAC, transporte seguro y custodia de secretos. */
@Configuration
@EnableConfigurationProperties(NotificationProperties.class)
public class WebhookRuntimeConfig {

    @Bean
    WebhookRepository webhookRepository(JdbcTemplate jdbc) {
        return new WebhookRepository(jdbc);
    }

    @Bean
    WebhookSecrets webhookSecrets(EnvelopeCrypto crypto, TenantKeyResolver keys) {
        return new WebhookSecrets(crypto, keys);
    }

    @Bean
    HmacSignatureService hmacSignatureService() {
        return new HmacSignatureService();
    }

    @Bean
    HostResolver hostResolver() {
        return HostResolver.system();
    }

    @Bean
    AddressPolicy addressPolicy() {
        return AddressPolicy.STRICT;
    }

    @Bean
    WebhookUrlPolicy webhookUrlPolicy(AddressPolicy addresses, NotificationProperties props,
                                      @Value("${idp.security.dev-mode:false}") boolean devMode) {
        if (props.allowInsecureHttp() && !devMode) {
            throw new IllegalStateException(
                "idp.notification.allow-insecure-http solo se admite con idp.security.dev-mode=true");
        }
        return new WebhookUrlPolicy(addresses, props.allowInsecureHttp());
    }

    @Bean
    SsrfGuard ssrfGuard(HostResolver resolver, AddressPolicy addresses) {
        return new SsrfGuard(resolver, addresses);
    }

    @Bean(destroyMethod = "close")
    ApacheWebhookTransport webhookTransport(SsrfGuard guard, NotificationProperties props) {
        NotificationProperties.Http h = props.http();
        return new ApacheWebhookTransport(guard, h.connectTimeout(), h.readTimeout(), h.totalTimeout());
    }

    @Bean
    WebhookDispatcher webhookDispatcher(WebhookTransport transport, WebhookUrlPolicy urlPolicy,
                                        HmacSignatureService hmac, Clock clock) {
        return new WebhookDispatcher(transport, urlPolicy, hmac, clock);
    }
}
