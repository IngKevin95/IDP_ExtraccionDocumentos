package com.idp.chat;

import com.idp.kms.InMemoryKeyService;
import com.idp.kms.KeyService;
import com.idp.llm.EmbeddingProvider;
import com.idp.llm.LlmProvider;
import com.idp.llm.LlmRequest;
import com.idp.llm.LlmResponse;
import com.idp.security.RoleAssignmentSource;
import com.idp.storage.ObjectMetadata;
import com.idp.storage.ObjectStore;
import com.idp.tenant.TenantId;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Dobles de los puertos externos: bucket y KMS en memoria, roles mutables, embeddings y LLM deterministas. */
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
    MutableTenantDirectory testTenantDirectory() {
        return new MutableTenantDirectory();
    }

    @Bean
    @Primary
    FakeEmbeddings testEmbeddings() {
        return new FakeEmbeddings();
    }

    @Bean
    @Primary
    ScriptedLlm testLlm() {
        return new ScriptedLlm();
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

    public static final class MutableTenantDirectory implements com.idp.tenant.context.TenantDirectory {
        private final Set<String> active = ConcurrentHashMap.newKeySet();

        public void add(String tenant) {
            active.add(tenant);
        }

        @Override
        public List<String> activeTenants() {
            return List.copyOf(active);
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

    /**
     * Embeddings de 8 dimensiones por palabras clave: ejes 0-3 son temas (monto/embargo, juzgado/radicado,
     * vigencia/plazo, titular/cuenta) y 4-7 un ruido determinista por texto. Preguntas del mismo tema pero distintas
     * quedan por debajo de 0.98 de similitud; el mismo texto da exactamente el mismo vector.
     */
    public static final class FakeEmbeddings implements EmbeddingProvider {
        public static final int DIMENSION = 8;
        private final AtomicInteger batchCalls = new AtomicInteger();
        private final AtomicInteger texts = new AtomicInteger();

        public int batchCalls() {
            return batchCalls.get();
        }

        public int texts() {
            return texts.get();
        }

        @Override
        public int dimension() {
            return DIMENSION;
        }

        @Override
        public List<float[]> embedBatch(TenantId tenantId, List<String> input) {
            batchCalls.incrementAndGet();
            texts.addAndGet(input.size());
            List<float[]> out = new ArrayList<>(input.size());
            for (String t : input) {
                out.add(vector(t));
            }
            return out;
        }

        public static float[] vector(String text) {
            String n = text.toLowerCase(Locale.ROOT);
            float[] v = new float[DIMENSION];
            v[0] = has(n, "monto", "embargo", "embargado") ? 1f : 0f;
            v[1] = has(n, "juzgado", "radicado") ? 1f : 0f;
            v[2] = has(n, "vigencia", "plazo") ? 1f : 0f;
            v[3] = has(n, "titular", "cuenta") ? 1f : 0f;
            Random r = new Random(text.hashCode());
            for (int i = 4; i < DIMENSION; i++) {
                v[i] = 0.4f * (r.nextFloat() * 2f - 1f);
            }
            double norm = 0;
            for (float x : v) {
                norm += x * x;
            }
            norm = Math.sqrt(norm);
            for (int i = 0; i < v.length; i++) {
                v[i] = (float) (v[i] / norm);
            }
            return v;
        }

        private static boolean has(String text, String... words) {
            for (String w : words) {
                if (text.contains(w)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** LLM programable: por defecto cita literalmente el inicio del primer fragmento del prompt. */
    public static final class ScriptedLlm implements LlmProvider {
        private static final Pattern FRAGMENT = Pattern.compile(
                "<<<FRAGMENTO id=([0-9a-f-]{36}) pagina=\\d+ nonce=[^>]*>>>\\n(.*?)\\n<<<FIN FRAGMENTO",
                Pattern.DOTALL);

        private final AtomicInteger calls = new AtomicInteger();
        private final List<String> prompts = new CopyOnWriteArrayList<>();
        private volatile Function<String, LlmResponse> behavior = ScriptedLlm::citeFirstFragment;

        public int calls() {
            return calls.get();
        }

        public List<String> prompts() {
            return prompts;
        }

        public void reset() {
            behavior = ScriptedLlm::citeFirstFragment;
        }

        public void respond(Function<String, LlmResponse> f) {
            behavior = f;
        }

        public void respondText(String text) {
            behavior = p -> reply(text);
        }

        @Override
        public LlmResponse generate(LlmRequest request) {
            calls.incrementAndGet();
            prompts.add(request.prompt());
            return behavior.apply(request.prompt());
        }

        public static LlmResponse reply(String text) {
            return new LlmResponse(text, "fake-1", "stop", new LlmResponse.Usage(1, 1));
        }

        public static LlmResponse citeFirstFragment(String prompt) {
            Matcher m = FRAGMENT.matcher(prompt);
            if (!m.find()) {
                return reply("Información insuficiente");
            }
            String content = m.group(2);
            String quote = content.substring(0, Math.min(40, content.length())).strip();
            return reply("Respuesta basada en el documento. [chunk:" + m.group(1) + "] \"" + quote + "\"");
        }

        /** Id del primer fragmento y su contenido en el prompt. */
        public static String[] firstFragment(String prompt) {
            Matcher m = FRAGMENT.matcher(prompt);
            if (!m.find()) {
                throw new IllegalStateException("Sin fragmentos en el prompt");
            }
            return new String[] {m.group(1), m.group(2)};
        }
    }
}
