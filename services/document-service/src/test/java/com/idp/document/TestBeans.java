package com.idp.document;

import com.idp.document.infra.RendererClient;
import com.idp.kms.InMemoryKeyService;
import com.idp.kms.KeyService;
import com.idp.security.RoleAssignmentSource;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Dobles de los puertos externos: bucket en memoria, KMS en memoria, roles en memoria y renderer falso. */
@TestConfiguration
public class TestBeans {

    @Bean
    @Primary
    InMemoryObjectStore testObjectStore() {
        return new InMemoryObjectStore();
    }

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
    FakeRenderer testRenderer() {
        return new FakeRenderer();
    }

    public static final class InMemoryObjectStore implements ObjectStore {
        private final Map<String, byte[]> data = new ConcurrentHashMap<>();

        @Override
        public void put(TenantId tenantId, String path, InputStream in, ObjectMetadata metadata) {
            try {
                data.put(tenantId.value() + "/" + path, in.readAllBytes());
            } catch (IOException e) {
                throw new StorageException("io");
            }
        }

        @Override
        public InputStream get(TenantId tenantId, String path) {
            byte[] b = data.get(tenantId.value() + "/" + path);
            if (b == null) {
                throw new StorageException("Objeto no encontrado");
            }
            return new ByteArrayInputStream(b);
        }

        @Override
        public void delete(TenantId tenantId, String path) {
            data.remove(tenantId.value() + "/" + path);
        }

        public Set<String> keys(String tenantId) {
            Set<String> out = new TreeSet<>();
            data.keySet().stream().filter(k -> k.startsWith(tenantId + "/")).forEach(out::add);
            return out;
        }

        public byte[] raw(String tenantId, String path) {
            return data.get(tenantId + "/" + path);
        }
    }

    public static final class MutableRoleSource implements RoleAssignmentSource {
        private final Set<String> grants = ConcurrentHashMap.newKeySet();

        public void grant(String tenant, String user, String role) {
            grants.add(tenant + "|" + user + "|" + role);
        }

        @Override
        public boolean hasRole(String tenantId, String userId, String role) {
            return grants.contains(tenantId + "|" + userId + "|" + role);
        }
    }

    public static final class FakeRenderer implements RendererClient {
        public enum Mode { OK, REJECT, UNAVAILABLE }

        public volatile Mode mode = Mode.OK;
        public volatile String rejectCode = "ERR_MALWARE_DETECTED";
        public volatile int rejectStatus = 422;
        public final AtomicInteger calls = new AtomicInteger();

        public void reset() {
            mode = Mode.OK;
            calls.set(0);
        }

        @Override
        public RenderResult render(String filename, String contentType, byte[] content) {
            calls.incrementAndGet();
            return switch (mode) {
                case OK -> new RenderResult(List.of(new Page(1, new byte[] {1, 2, 3}), new Page(2, new byte[] {4, 5})),
                        "{\"pages\":[]}".getBytes());
                case REJECT -> throw new RejectedException(rejectCode, rejectStatus);
                case UNAVAILABLE -> throw new UnavailableException("renderer caido", null);
            };
        }
    }
}
