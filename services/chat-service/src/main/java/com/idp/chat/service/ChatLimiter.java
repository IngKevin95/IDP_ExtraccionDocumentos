package com.idp.chat.service;

import com.idp.chat.config.ChatProperties;
import com.idp.chat.infra.TokenUsageRepository;
import com.idp.chat.service.Exceptions.RateLimitedException;
import io.github.resilience4j.ratelimiter.RateLimiter;
import io.github.resilience4j.ratelimiter.RateLimiterConfig;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Limites de uso del chat (SEC-049, ADR 0023), aplicados antes de gastar embeddings o LLM:
 * <ul>
 * <li>tasa por (tenant, usuario) y por tenant, ventana de un minuto (Resilience4j RateLimiter, sin espera): al
 * superarla 429 {@code CHAT_RATE_LIMITED} con Retry-After (la ventana completa: cota superior segura);</li>
 * <li>tope diario de tokens del LLM por tenant, contado en el silo ({@code chat_token_usage}, dia UTC, sobrevive a
 * reinicios y a varias replicas): al alcanzarlo 429 {@code CHAT_QUOTA_EXCEEDED} con Retry-After hasta las 00:00 UTC.
 * La comprobacion es previa a la llamada, asi que el exceso maximo es el de las llamadas ya en vuelo; los tokens de
 * embeddings no se cuentan (el puerto no los informa).</li>
 * </ul>
 * Los limitadores por usuario viven en memoria por replica (el limite efectivo crece con el numero de replicas) y se
 * reinician si el mapa supera {@link #MAX_USER_LIMITERS}; solo se crean para identidades ya autenticadas y autorizadas.
 */
@Component
public class ChatLimiter {

    static final int MAX_USER_LIMITERS = 50_000;
    private static final Duration WINDOW = Duration.ofMinutes(1);

    private final ChatProperties.Limits limits;
    private final TokenUsageRepository usage;
    private final Map<String, RateLimiter> perUser = new ConcurrentHashMap<>();
    private final Map<String, RateLimiter> perTenant = new ConcurrentHashMap<>();

    public ChatLimiter(ChatProperties props, TokenUsageRepository usage) {
        this.limits = props.limits();
        this.usage = usage;
    }

    /** @throws RateLimitedException tasa por usuario o por tenant superada */
    public void checkRate(String tenantId, String userId) {
        if (perUser.size() > MAX_USER_LIMITERS) {
            perUser.clear();
        }
        boolean tenantOk = perTenant.computeIfAbsent(tenantId, k -> limiter(limits.tenantPerMinute()))
                .acquirePermission();
        boolean userOk = tenantOk && perUser.computeIfAbsent(tenantId + '|' + userId,
                k -> limiter(limits.userPerMinute())).acquirePermission();
        if (!tenantOk || !userOk) {
            throw new RateLimitedException(RateLimitedException.RATE, WINDOW.toSeconds());
        }
    }

    /** @throws RateLimitedException tope diario de tokens del tenant alcanzado */
    public void checkQuota() {
        if (limits.dailyTokens() > 0 && usage.usedToday() >= limits.dailyTokens()) {
            ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
            long untilMidnight = Duration.between(now, now.toLocalDate().plusDays(1).atStartOfDay(ZoneOffset.UTC))
                    .toSeconds();
            throw new RateLimitedException(RateLimitedException.QUOTA, untilMidnight);
        }
    }

    private static RateLimiter limiter(int perMinute) {
        return RateLimiter.of("chat", RateLimiterConfig.custom().limitForPeriod(perMinute)
                .limitRefreshPeriod(WINDOW).timeoutDuration(Duration.ZERO).build());
    }
}
