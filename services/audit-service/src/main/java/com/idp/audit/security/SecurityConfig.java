package com.idp.audit.security;

import com.idp.security.CachingRoleAssignmentVerifier;
import com.idp.security.JdbcRoleAssignmentSource;
import com.idp.security.TenantAuthorizer;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource Server JWT (solo identidad). El tenant sale siempre del claim tenant_id (nunca del path) y el rol AUDITOR se
 * revalida por request contra role_assignment con cache de 30 s maximo ({@link AuditorRevalidationFilter}).
 * La verificacion de firma de expedientes es publica: solo usa la llave de verificacion.
 */
@Configuration
@EnableWebSecurity
@org.springframework.context.annotation.Import(com.idp.security.IdpJwtConfiguration.class)
public class SecurityConfig {

    public static final String AUDITOR = com.idp.security.Roles.AUDITOR;
    public static final String PUBLIC_PREFIX = "/v1/audit/public/";

    @Bean
    CachingRoleAssignmentVerifier roleVerifier(JdbcTemplate jdbc, Clock clock,
            @Value("${idp.audit.revalidation.cache-ttl-seconds:30}") long ttlSeconds) {
        return new CachingRoleAssignmentVerifier(new JdbcRoleAssignmentSource(jdbc), clock,
                Duration.ofSeconds(Math.max(1, Math.min(ttlSeconds, 30))));
    }

    /** acceso.revocado purga la cache de roles de este servicio (H10). */
    @Bean
    com.idp.security.AccesoRevocadoKafkaListener accesoRevocadoListener(CachingRoleAssignmentVerifier verifier,
                                                                         com.idp.events.EventSerde serde) {
        return new com.idp.security.AccesoRevocadoKafkaListener(verifier, serde);
    }

    @Bean
    TenantAuthorizer tenantAuthorizer(CachingRoleAssignmentVerifier verifier) {
        return new TenantAuthorizer(verifier);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TenantAuthorizer authorizer) throws Exception {
        // El endpoint publico de verificacion no tiene sesion ni credenciales: se excluye junto a las peticiones bearer.
        http.csrf(c -> c.ignoringRequestMatchers(new com.idp.security.NoAmbientCredentialsRequestMatcher(),
                        org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher.withDefaults()
                                .matcher(HttpMethod.POST, PUBLIC_PREFIX + "**")))
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health",
                                "/actuator/prometheus").permitAll()
                        .requestMatchers(HttpMethod.POST, PUBLIC_PREFIX + "**").permitAll()
                        .requestMatchers("/v1/audit/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> { }))
                .addFilterAfter(new AuditorRevalidationFilter(authorizer), BearerTokenAuthenticationFilter.class);
        return http.build();
    }
}
