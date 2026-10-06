package com.idp.notification.support;

import com.idp.kms.InMemoryKeyService;
import com.idp.kms.KeyService;
import com.idp.notification.net.AddressPolicy;
import com.idp.security.RoleAssignmentSource;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Dobles de los puertos externos: KMS y roles en memoria, reloj controlable, DNS falso y una politica de
 * direcciones que SOLO permite loopback (para llegar al receptor local de pruebas) y aplica la estricta al resto.
 */
@TestConfiguration
public class TestBeans {

    @Bean
    @Primary
    KeyService testKeyService() {
        return new InMemoryKeyService();
    }

    @Bean
    @Primary
    MutableRoleSource testRoleSource() {
        return new MutableRoleSource();
    }

    @Bean
    @Primary
    MutableClock testClock() {
        return new MutableClock();
    }

    @Bean
    @Primary
    FakeHostResolver testHostResolver() {
        return new FakeHostResolver();
    }

    @Bean
    @Primary
    FakeDomainOwnershipVerifier testDomainVerifier() {
        return new FakeDomainOwnershipVerifier();
    }

    @Bean
    @Primary
    AddressPolicy testAddressPolicy() {
        return a -> !a.isLoopbackAddress() && AddressPolicy.STRICT.isBlocked(a);
    }

    public static final class MutableRoleSource implements RoleAssignmentSource {
        private final Set<String> grants = ConcurrentHashMap.newKeySet();

        public void grant(String tenant, String user, String role) {
            grants.add(tenant + "|" + user + "|" + role);
        }

        public void revoke(String tenant, String user, String role) {
            grants.remove(tenant + "|" + user + "|" + role);
        }

        @Override
        public boolean hasRole(String tenantId, String userId, String role) {
            return grants.contains(tenantId + "|" + userId + "|" + role);
        }
    }
}
