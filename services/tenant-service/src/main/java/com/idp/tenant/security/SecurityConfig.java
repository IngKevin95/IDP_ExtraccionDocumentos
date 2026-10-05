package com.idp.tenant.security;

import com.idp.security.TenantAuthorizer;
import com.idp.tenant.infrastructure.persistence.TenantRepository;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource Server JWT. Plataforma: rol PLATFORM_ADMIN en el token (red de administracion). Tenant: el
 * tenant sale del claim tenant_id y se revalida por request contra la base de control (nunca del path).
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {
    public static final String PLATFORM_ADMIN = com.idp.security.Roles.PLATFORM_ADMIN;

    @Bean
    TenantAuthorizer tenantAuthorizer(CachingRoleAssignmentVerifier verifier) {
        return new TenantAuthorizer(verifier);
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, TenantAuthorizer authorizer,
                                            TenantRepository tenants) throws Exception {
        http.csrf(c -> c.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(a -> a
                        .requestMatchers(HttpMethod.GET, "/actuator/health/**", "/actuator/health",
                                "/actuator/info", "/actuator/prometheus").permitAll()
                        .requestMatchers("/v1/admin/**").hasRole(PLATFORM_ADMIN)
                        .requestMatchers("/v1/tenant/**").authenticated()
                        .anyRequest().denyAll())
                .oauth2ResourceServer(o -> o.jwt(j -> j.jwtAuthenticationConverter(jwtConverter())))
                .addFilterAfter(new TenantRevalidationFilter(authorizer, tenants), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    static JwtAuthenticationConverter jwtConverter() {
        JwtAuthenticationConverter c = new JwtAuthenticationConverter();
        c.setJwtGrantedAuthoritiesConverter(SecurityConfig::authorities);
        return c;
    }

    private static Collection<GrantedAuthority> authorities(Jwt jwt) {
        Object roles = jwt.getClaims().get("roles");
        if (roles == null && jwt.getClaims().get("realm_access") instanceof Map<?, ?> ra) {
            roles = ra.get("roles");
        }
        if (roles instanceof Collection<?> col) {
            return col.stream().map(r -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r)).toList();
        }
        return List.of();
    }
}
