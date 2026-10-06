package com.idp.notification.http;

import com.idp.notification.net.SsrfGuard;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.config.TlsConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.io.entity.ByteArrayEntity;
import org.apache.hc.core5.http.ssl.TLS;
import org.apache.hc.core5.util.Timeout;

/**
 * Cliente saliente (SEC-027): DNS resuelto una vez y validado por {@link SsrfGuard} (el socket conecta a esa IP),
 * redirects deshabilitados, sin reintentos automaticos, sin reutilizar conexiones (cada envio vuelve a validar el
 * destino), sin proxy ni cookies, TLS 1.2+ con verificacion de certificado y nombre, y plazo total por envio
 * (anti-tarpit). La respuesta no se lee: solo el codigo HTTP.
 */
public final class ApacheWebhookTransport implements WebhookTransport, AutoCloseable {

    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "webhook-deadline");
        t.setDaemon(true);
        return t;
    });

    private final CloseableHttpClient client;
    private final Duration totalTimeout;

    public ApacheWebhookTransport(SsrfGuard guard, Duration connectTimeout, Duration readTimeout,
                                  Duration totalTimeout) {
        this.totalTimeout = totalTimeout;
        this.client = HttpClients.custom()
            .setConnectionManager(PoolingHttpClientConnectionManagerBuilder.create()
                .setDnsResolver(guard)
                .setDefaultConnectionConfig(ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(connectTimeout))
                    .setSocketTimeout(Timeout.of(readTimeout))
                    .build())
                .setDefaultTlsConfig(TlsConfig.custom().setSupportedProtocols(TLS.V_1_3, TLS.V_1_2).build())
                .build())
            .setDefaultRequestConfig(RequestConfig.custom()
                .setResponseTimeout(Timeout.of(readTimeout))
                .setConnectionRequestTimeout(Timeout.of(connectTimeout))
                .build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableContentCompression()
            .setConnectionReuseStrategy((request, response, context) -> false)
            .setUserAgent("IDP-Webhook/1.0")
            .build();
    }

    @Override
    public int post(URI uri, byte[] body, Map<String, String> headers) throws IOException {
        HttpPost post = new HttpPost(uri);
        headers.forEach(post::setHeader);
        post.setEntity(new ByteArrayEntity(body, ContentType.APPLICATION_JSON));
        ScheduledFuture<?> deadline = WATCHDOG.schedule(post::cancel, totalTimeout.toMillis(), TimeUnit.MILLISECONDS);
        try (ClassicHttpResponse response = client.executeOpen(null, post, null)) {
            return response.getCode();
        } finally {
            deadline.cancel(false);
        }
    }

    @Override
    public void close() throws IOException {
        client.close();
    }
}
