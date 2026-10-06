package com.idp.notification.service;

import com.idp.notification.config.NotificationProperties;
import com.idp.notification.config.NotificationProperties.HostVerification;
import com.idp.notification.net.DomainOwnershipVerifier;
import com.idp.notification.net.PublicSuffixes;
import com.idp.notification.service.Exceptions.ConflictException;
import com.idp.notification.service.Exceptions.InvalidRequestException;
import com.idp.notification.store.WebhookRepository;
import com.idp.notification.store.WebhookRepository.HostChallenge;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Control de la allowlist de hosts del tenant (SEC-027, ADR 0017). El TENANT_ADMIN fija la lista, asi que la lista
 * por si sola no es una frontera de confianza. Este componente (1) rechaza comodines y entradas sobre sufijos
 * publicos o de una sola etiqueta y (2) exige, para cada host nuevo, prueba de propiedad del dominio por TXT DNS
 * ({@code DNS}) o aprobacion explicita de plataforma ({@code PLATFORM}); {@code NONE} solo existe en dev-mode.
 */
@Component
public class HostAllowlistService {

    static final Pattern SHAPE = Pattern.compile("(\\*\\.)?[a-z0-9]([a-z0-9.-]{0,251}[a-z0-9])?");
    private static final Logger LOG = LoggerFactory.getLogger(HostAllowlistService.class);

    private final WebhookRepository repository;
    private final DomainOwnershipVerifier verifier;
    private final NotificationProperties props;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public HostAllowlistService(WebhookRepository repository, DomainOwnershipVerifier verifier,
                                NotificationProperties props, Clock clock) {
        this.repository = repository;
        this.verifier = verifier;
        this.props = props;
        this.clock = clock;
    }

    /** Forma de una entrada ya normalizada (minusculas): sintaxis, y sin comodines/entradas sobre sufijos publicos. */
    public static void validateShape(String host) {
        if (!SHAPE.matcher(host).matches()) {
            throw new InvalidRequestException("WEBHOOK_POLICY_INVALID", "allowedHosts invalido");
        }
        boolean wildcard = host.startsWith("*.");
        String domain = wildcard ? host.substring(2) : host;
        if (wildcard ? !PublicSuffixes.wildcardBaseAllowed(domain) : PublicSuffixes.isPublicSuffix(domain)) {
            throw new InvalidRequestException("WEBHOOK_HOST_PUBLIC_SUFFIX",
                "allowedHosts no admite sufijos publicos ni comodines sobre ellos: " + host);
        }
    }

    /**
     * Valida la lista solicitada: forma de cada entrada y, para los hosts que no estaban ya en la lista vigente,
     * activacion segun el modo de verificacion. Lanza si algun host nuevo no esta verificado/aprobado.
     */
    public void authorize(UUID tenant, List<String> current, List<String> requested) {
        requested.forEach(HostAllowlistService::validateShape);
        List<String> existing = current == null ? List.of() : current;
        for (String host : requested) {
            if (!existing.contains(host)) {
                activate(tenant, host);
            }
        }
    }

    private void activate(UUID tenant, String host) {
        HostVerification mode = props.hostVerification();
        if (mode == HostVerification.NONE) {
            return;
        }
        String domain = host.startsWith("*.") ? host.substring(2) : host;
        if (mode == HostVerification.PLATFORM) {
            List<String> approved = props.platformApprovedHosts().getOrDefault(tenant.toString(), List.of());
            if (approved.stream().noneMatch(a -> a.strip().toLowerCase(Locale.ROOT).equals(host))) {
                throw new ConflictException("WEBHOOK_HOST_PENDING_PLATFORM_APPROVAL",
                    "El host " + host + " requiere aprobacion de plataforma");
            }
            return;
        }
        HostChallenge challenge = repository.findHostChallenge(tenant, domain).orElse(null);
        if (challenge != null && challenge.verified()) {
            return;
        }
        if (challenge == null) {
            String token = newToken();
            repository.insertHostChallenge(tenant, domain, token, clock.instant());
            challenge = repository.findHostChallenge(tenant, domain).orElseThrow();
        }
        if (verifier.owns(domain, challenge.token())) {
            repository.markHostVerified(tenant, domain, clock.instant());
            LOG.info("Dominio {} verificado para el tenant {}", domain, tenant);
            return;
        }
        throw new ConflictException("WEBHOOK_HOST_UNVERIFIED", "Host " + host + " sin verificar: publique el TXT "
            + DomainOwnershipVerifier.challengeName(domain) + " = " + DomainOwnershipVerifier.challengeValue(
                challenge.token()) + " y repita la solicitud");
    }

    private String newToken() {
        byte[] raw = new byte[24];
        random.nextBytes(raw);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
    }
}
