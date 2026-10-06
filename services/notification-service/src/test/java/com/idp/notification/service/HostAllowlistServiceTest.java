package com.idp.notification.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idp.notification.config.NotificationProperties;
import com.idp.notification.config.NotificationProperties.HostVerification;
import com.idp.notification.service.Exceptions.ConflictException;
import com.idp.notification.service.Exceptions.InvalidRequestException;
import com.idp.notification.store.WebhookRepository;
import com.idp.notification.store.WebhookRepository.HostChallenge;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** SEC-027 (hallazgo 1): sufijos publicos y verificacion de propiedad antes de activar un host nuevo. */
class HostAllowlistServiceTest {

    private static final UUID TENANT = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private final WebhookRepository repo = mock(WebhookRepository.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final AtomicBoolean ownsDomain = new AtomicBoolean();

    private static NotificationProperties props(HostVerification mode, Map<String, List<String>> approved) {
        return new NotificationProperties(5, Duration.ofSeconds(30), 2.0, Duration.ofHours(1), Duration.ofDays(7), false,
            new NotificationProperties.Http(Duration.ofSeconds(3), Duration.ofSeconds(5), Duration.ofSeconds(10)),
            new NotificationProperties.Worker(true, Duration.ofSeconds(1), 10, Duration.ofMinutes(5), 8, 16, 2, 2,
                Duration.ofSeconds(20)),
            mode, approved, null, 3, new NotificationProperties.Breaker(50f, 10, 5, Duration.ofSeconds(60), 2));
    }

    private HostAllowlistService service(HostVerification mode, Map<String, List<String>> approved) {
        return new HostAllowlistService(repo, (domain, token) -> ownsDomain.get(), props(mode, approved), clock);
    }

    @ParameterizedTest
    @ValueSource(strings = {"*.com", "*.co.uk", "*.com.ar", "*.github.io", "com", "co.uk", "*.herokuapp.com", "localhost"})
    void rechazaComodinesYEntradasSobreSufijosPublicosYEtiquetasUnicas(String host) {
        HostAllowlistService svc = service(HostVerification.NONE, Map.of());
        assertThatThrownBy(() -> svc.authorize(TENANT, List.of(), List.of(host)))
            .isInstanceOf(InvalidRequestException.class)
            .extracting(e -> ((InvalidRequestException) e).errorCode()).isEqualTo("WEBHOOK_HOST_PUBLIC_SUFFIX");
    }

    @Test
    void rechazaSintaxisInvalida() {
        HostAllowlistService svc = service(HostVerification.NONE, Map.of());
        for (String bad : List.of("https://x.banco.com", "x.banco.com/path", "*", "*.", "a b.com")) {
            assertThatThrownBy(() -> svc.authorize(TENANT, List.of(), List.of(bad)))
                .isInstanceOf(InvalidRequestException.class);
        }
    }

    @Test
    void dnsHostNuevoSinTxtGeneraDesafioYSeRechazaConElToken() {
        when(repo.findHostChallenge(TENANT, "banco.com")).thenReturn(Optional.empty())
            .thenReturn(Optional.of(new HostChallenge("banco.com", "tok-abc", false)));
        HostAllowlistService svc = service(HostVerification.DNS, Map.of());
        assertThatThrownBy(() -> svc.authorize(TENANT, List.of(), List.of("*.banco.com")))
            .isInstanceOf(ConflictException.class)
            .hasMessageContaining("_idp-verify.banco.com").hasMessageContaining("idp-verify=tok-abc")
            .extracting(e -> ((ConflictException) e).errorCode()).isEqualTo("WEBHOOK_HOST_UNVERIFIED");
        verify(repo).insertHostChallenge(eq(TENANT), eq("banco.com"), any(), any());
        verify(repo, never()).markHostVerified(any(), any(), any());
    }

    @Test
    void dnsConTxtPublicadoVerificaYActivaElHost() {
        when(repo.findHostChallenge(TENANT, "banco.com"))
            .thenReturn(Optional.of(new HostChallenge("banco.com", "tok-abc", false)));
        ownsDomain.set(true);
        service(HostVerification.DNS, Map.of()).authorize(TENANT, List.of(), List.of("*.banco.com"));
        verify(repo).markHostVerified(eq(TENANT), eq("banco.com"), any());
    }

    @Test
    void dnsDominioYaVerificadoNoConsultaDnsYHostExistenteNoSeReverifica() {
        when(repo.findHostChallenge(TENANT, "banco.com"))
            .thenReturn(Optional.of(new HostChallenge("banco.com", "tok-abc", true)));
        HostAllowlistService svc = service(HostVerification.DNS, Map.of());
        svc.authorize(TENANT, List.of(), List.of("*.banco.com"));
        // "viejo.com" ya estaba en la politica: no genera desafio ni consulta.
        svc.authorize(TENANT, List.of("viejo.com"), List.of("viejo.com"));
        verify(repo, never()).findHostChallenge(TENANT, "viejo.com");
        verify(repo, never()).insertHostChallenge(any(), any(), any(), any());
    }

    @Test
    void plataformaExigeAprobacionExplicitaPorTenant() {
        HostAllowlistService svc = service(HostVerification.PLATFORM, Map.of(TENANT.toString(), List.of("*.banco.com")));
        svc.authorize(TENANT, List.of(), List.of("*.banco.com"));
        assertThatThrownBy(() -> svc.authorize(TENANT, List.of(), List.of("otro.com")))
            .isInstanceOf(ConflictException.class)
            .extracting(e -> ((ConflictException) e).errorCode()).isEqualTo("WEBHOOK_HOST_PENDING_PLATFORM_APPROVAL");
        UUID other = UUID.randomUUID();
        assertThatThrownBy(() -> svc.authorize(other, List.of(), List.of("*.banco.com")))
            .isInstanceOf(ConflictException.class);
    }

    @Test
    void modoNoneNoExigeVerificacionPeroSiRechazaSufijosPublicos() {
        HostAllowlistService svc = service(HostVerification.NONE, Map.of());
        svc.authorize(TENANT, List.of(), List.of("*.banco.com", "api.otro.com"));
        verify(repo, never()).findHostChallenge(any(), any());
    }
}
