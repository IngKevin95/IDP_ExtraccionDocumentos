package com.idp.review;

import com.idp.kms.InMemoryKeyService;
import com.idp.kms.KeyService;
import com.idp.review.domain.FieldCandidate;
import com.idp.review.infra.FieldCandidateSource;
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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Dobles de los puertos externos: bucket y KMS en memoria, roles mutables y campos dudosos programables. */
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
    StubFields testFieldSource() {
        return new StubFields();
    }

    @Bean
    org.springframework.web.client.RestClient.Builder testRestClientBuilder() {
        return org.springframework.web.client.RestClient.builder();
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

    /** Campos dudosos por tarea; sin programar devuelve vacio. */
    public static final class StubFields implements FieldCandidateSource {
        private final Map<UUID, List<FieldCandidate>> byTask = new ConcurrentHashMap<>();

        public void program(UUID taskId, FieldCandidate... fields) {
            byTask.put(taskId, List.of(fields));
        }

        private final Map<UUID, List<FieldCandidate>> byDocument = new ConcurrentHashMap<>();
        private final Map<UUID, String> uploaders = new ConcurrentHashMap<>();

        /** Campos de la ultima extraccion de un documento ya aprobado (revision ciega). */
        public void programApproved(UUID documentId, FieldCandidate... fields) {
            byDocument.put(documentId, List.of(fields));
        }

        public void uploadedBy(UUID documentId, String user) {
            uploaders.put(documentId, user);
        }

        @Override
        public List<FieldCandidate> candidates(UUID documentId, UUID taskId) {
            return byTask.getOrDefault(taskId, List.of());
        }

        @Override
        public List<FieldCandidate> approvedFields(UUID documentId) {
            return byDocument.getOrDefault(documentId, List.of());
        }

        @Override
        public java.util.Optional<String> uploader(UUID documentId) {
            return java.util.Optional.ofNullable(uploaders.get(documentId));
        }
    }
}
