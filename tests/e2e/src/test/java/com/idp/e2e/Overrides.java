package com.idp.e2e;

import com.idp.document.infra.RendererClient;
import com.idp.kms.KeyService;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantConnection;
import com.idp.tenant.context.TenantCredentialProvider;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.interfaces.RSAPublicKey;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

/** Dobles de los puertos externos compartidos por los servicios levantados en la misma JVM. */
final class Overrides {

    private Overrides() {
    }

    /** Estado compartido entre contextos: bucket, KMS, renderer, LLM y llave publica del emisor de JWT. */
    static final class Shared {
        static final InMemoryObjectStore STORE = new InMemoryObjectStore();
        static final com.idp.kms.InMemoryKeyService KEYS = new com.idp.kms.InMemoryKeyService();
        static final FakeRenderer RENDERER = new FakeRenderer();
        static final DeterministicLlm LLM = new DeterministicLlm();
        static volatile RSAPublicKey jwtPublicKey;
        static final String ISSUER = "https://idp.test/tenants";

        private Shared() {
        }
    }

    static final class InMemoryObjectStore implements ObjectStore {
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
                throw new StorageException(com.idp.storage.EncryptedArtifactStore.NOT_FOUND_MESSAGE);
            }
            return new ByteArrayInputStream(b);
        }

        @Override
        public void delete(TenantId tenantId, String path) {
            data.remove(tenantId.value() + "/" + path);
        }

        Set<String> keys(String tenantId) {
            Set<String> out = new TreeSet<>();
            data.keySet().stream().filter(k -> k.startsWith(tenantId + "/")).forEach(out::add);
            return out;
        }

        byte[] raw(String tenantId, String path) {
            return data.get(tenantId + "/" + path);
        }
    }

    /** Renderer falso: 1 pagina PNG y capa de texto con el oficio EC sintetico. */
    static final class FakeRenderer implements RendererClient {
        static final String OFICIO_TEXT = "OFICIO 123 de 2026. Radicado 11001-31-03-005-2024-00123-00. "
            + "Ciudad Bogota D.C. Fecha 2026-09-30. Embargo coactivo contra los demandados. "
            + "Valor total $15.000.000 (quince millones de pesos). Cuenta de ahorros 123456789. "
            + "Demandado Maria Perez C.C. 1.234.567 $10.000.000 Cuenta de ahorros 123456789. "
            + "Demandado Comercial SAS NIT 900.123.456-8 $5.000.000 cuenta corriente 987654321.";

        final AtomicInteger calls = new AtomicInteger();

        @Override
        public RenderResult render(String filename, String contentType, byte[] content) {
            calls.incrementAndGet();
            String layer = "{\"pages\":[{\"page\":1,\"text\":\"" + OFICIO_TEXT + "\"}]}";
            return new RenderResult(List.of(new Page(1, new byte[] {1, 2, 3})), layer.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class DocumentOverrides {
        @Bean
        @Primary
        ObjectStore e2eObjectStore() {
            return Shared.STORE;
        }

        @Bean
        KeyService e2eKeyService() {
            return Shared.KEYS;
        }

        @Bean
        @Primary
        RendererClient e2eRendererClient() {
            return Shared.RENDERER;
        }

        @Bean
        @Primary
        JwtDecoder e2eJwtDecoder() {
            return decoder("document-service");
        }
    }

    @Configuration(proxyBeanMethods = false)
    public static class AuditOverrides {
        @Bean
        @Primary
        JwtDecoder e2eJwtDecoder() {
            return decoder("audit-service");
        }
    }

    /** Decodificador con la llave del emisor de prueba y los mismos validadores (issuer y audiencia) de produccion. */
    static JwtDecoder decoder(String audience) {
        NimbusJwtDecoder d = NimbusJwtDecoder.withPublicKey(Shared.jwtPublicKey).build();
        d.setJwtValidator(com.idp.security.IdpJwtConfiguration.validators(Shared.ISSUER, audience));
        return d;
    }

    @Configuration(proxyBeanMethods = false)
    public static class ExtractionOverrides {
        @Bean
        @Primary
        ObjectStore e2eObjectStore() {
            return Shared.STORE;
        }

        @Bean
        @Primary
        KeyService e2eKeyService() {
            return Shared.KEYS;
        }

        /** Silo H2 por tenant (sin OpenBao). */
        @Bean
        @Primary
        TenantCredentialProvider e2eCredentials() {
            return tenantId -> new TenantConnection(extractionUrl(tenantId), "sa", "");
        }

        /** ChatModel falso: el adaptador Spring AI real delega en el LlmProvider determinista. */
        @Bean
        ChatModel e2eChatModel() {
            return new ChatModel() {
                @Override
                public ChatResponse call(org.springframework.ai.chat.prompt.Prompt prompt) {
                    LlmResponse r = Shared.LLM.generate(new LlmRequest(null, prompt.getContents(), List.of(), null));
                    Generation g = new Generation(new AssistantMessage(r.content()),
                        ChatGenerationMetadata.builder().finishReason("STOP").build());
                    return new ChatResponse(List.of(g), ChatResponseMetadata.builder().model(r.modelVersion())
                        .usage(new DefaultUsage(r.usage().inputTokens(), r.usage().outputTokens())).build());
                }
            };
        }
    }

    static String extractionUrl(String tenantId) {
        return "jdbc:h2:mem:extr_" + tenantId.replace('-', '_') + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
    }
}
