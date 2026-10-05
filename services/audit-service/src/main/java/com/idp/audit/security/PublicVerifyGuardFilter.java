package com.idp.audit.security;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Proteccion del endpoint publico de verificacion de firma: cuerpo maximo (1 MB por defecto) y limite de tasa
 * por IP y global (token bucket en memoria). La IP es la resuelta por Tomcat (forward-headers-strategy=native):
 * solo el gateway llega al pod por NetworkPolicy, asi que X-Forwarded-For es confiable. El tope global acota
 * el abuso distribuido (muchas IPs). Responde 413 / 429 sin tocar el KMS ni la base.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class PublicVerifyGuardFilter extends OncePerRequestFilter {

    private static final class Bucket {
        private double tokens;
        private long lastNanos;

        Bucket(double tokens, long now) {
            this.tokens = tokens;
            this.lastNanos = now;
        }

        synchronized boolean tryTake(double capacity, double refillPerNano, long now) {
            tokens = Math.min(capacity, tokens + (now - lastNanos) * refillPerNano);
            lastNanos = now;
            if (tokens >= 1) {
                tokens -= 1;
                return true;
            }
            return false;
        }
    }

    private final long maxBodyBytes;
    private final int perMinute;
    private final int globalPerMinute;
    private final Bucket global;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(50_000).expireAfterAccess(Duration.ofMinutes(10)).build();

    public PublicVerifyGuardFilter(
            @Value("${idp.audit.public-verify.max-body-bytes:1048576}") long maxBodyBytes,
            @Value("${idp.audit.public-verify.rate-per-minute:30}") int perMinute,
            @Value("${idp.audit.public-verify.global-rate-per-minute:600}") int globalPerMinute) {
        this.maxBodyBytes = maxBodyBytes;
        this.perMinute = Math.max(1, perMinute);
        this.globalPerMinute = Math.max(1, globalPerMinute);
        this.global = new Bucket(this.globalPerMinute, System.nanoTime());
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith(SecurityConfig.PUBLIC_PREFIX);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        long now = System.nanoTime();
        // request.getRemoteAddr() ya es la IP resuelta por Tomcat (RemoteIpValve, strategy native).
        Bucket bucket = buckets.get(request.getRemoteAddr(), k -> new Bucket(perMinute, now));
        if (!bucket.tryTake(perMinute, perMinute / 60_000_000_000d, now)
                || !global.tryTake(globalPerMinute, globalPerMinute / 60_000_000_000d, now)) {
            response.setHeader("Retry-After", "60");
            response.sendError(429);
            return;
        }
        if (request.getContentLengthLong() > maxBodyBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        byte[] body = request.getInputStream().readNBytes((int) Math.min(Integer.MAX_VALUE, maxBodyBytes + 1));
        if (body.length > maxBodyBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE);
            return;
        }
        chain.doFilter(new CachedBodyRequest(request, body), response);
    }

    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body.clone();
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream in = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public int read() {
                    return in.read();
                }

                @Override
                public boolean isFinished() {
                    return in.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }

        @Override
        public int getContentLength() {
            return body.length;
        }

        @Override
        public long getContentLengthLong() {
            return body.length;
        }
    }
}
