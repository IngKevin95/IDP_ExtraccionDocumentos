package com.idp.quality;

import com.idp.quality.golden.ExtractionRunner;
import com.idp.quality.golden.FieldPrediction;
import com.idp.quality.golden.GoldenDocument;
import com.idp.security.RoleAssignmentSource;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Dobles de los puertos externos: roles en memoria y ejecutor de extraccion falso (sin llamadas a LLM). */
@TestConfiguration
public class TestBeans {

    @Bean
    @Primary
    MutableRoleSource testRoleSource() {
        return new MutableRoleSource();
    }

    @Bean
    @Primary
    FakeRunner testRunner() {
        return new FakeRunner();
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

    /** Predicciones generadas por clave modelo+prompt; sin registro la corrida falla como en produccion. */
    public static final class FakeRunner implements ExtractionRunner {
        private final Map<String, Function<List<GoldenDocument>, List<FieldPrediction>>> byKey =
            new ConcurrentHashMap<>();

        public void register(String key, Function<List<GoldenDocument>, List<FieldPrediction>> generator) {
            byKey.put(key, generator);
        }

        @Override
        public List<FieldPrediction> run(String modelPromptKey, List<GoldenDocument> documents) {
            Function<List<GoldenDocument>, List<FieldPrediction>> g = byKey.get(modelPromptKey);
            if (g == null) {
                throw new RunnerException("Sin resultados registrados para el par modelo+prompt");
            }
            return g.apply(documents);
        }
    }
}
