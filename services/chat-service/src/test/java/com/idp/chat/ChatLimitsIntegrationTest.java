package com.idp.chat;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

/**
 * SEC-049 / ADR 0023: tasa por (tenant, usuario) y por tenant, tope diario de tokens por tenant (429 con Retry-After,
 * antes de gastar embeddings o LLM) y timeout real hacia el LLM (503). Contexto propio con limites bajos.
 */
@TestPropertySource(properties = {
    "idp.chat.limits.user-per-minute=3",
    "idp.chat.limits.tenant-per-minute=5",
    "idp.chat.limits.daily-tokens=5",
    "idp.chat.llm-timeout=500ms"})
class ChatLimitsIntegrationTest extends AbstractChatIntegrationTest {

    static final String DOC = "El juzgado ordena el embargo por un monto de 1500000 pesos contra el titular.";

    private static String retryAfter(MvcResult r) {
        return r.getResponse().getHeader("Retry-After");
    }

    @Test
    void tasaPorUsuarioDa429ConRetryAfterYSinGastarEmbeddings() throws Exception {
        operator("ana");
        UUID s = session("ana", indexedDocument(DOC));
        for (int i = 0; i < 3; i++) {
            // Preguntas sin relacion con el documento: abstencion sin LLM (no consumen cuota de tokens).
            assertThat(status(ask("ana", s, "Que color tiene el cielo " + i))).isEqualTo(200);
        }
        int texts = embeddings.texts();
        int calls = llm.calls();

        MvcResult r = ask("ana", s, "Que color tiene el mar");

        assertThat(status(r)).isEqualTo(429);
        assertThat(body(r).path("code").asText()).isEqualTo("CHAT_RATE_LIMITED");
        assertThat(Long.parseLong(retryAfter(r))).isBetween(1L, 60L);
        assertThat(embeddings.texts()).isEqualTo(texts);
        assertThat(llm.calls()).isEqualTo(calls);
        // Otro usuario del mismo tenant no esta limitado por ana.
        operator("beto");
        UUID sb = session("beto", indexedDocument(DOC));
        assertThat(status(ask("beto", sb, "Que color tiene el cielo b"))).isEqualTo(200);
    }

    @Test
    void tasaPorTenantLimitaALosUsuariosEnConjunto() throws Exception {
        UUID doc = indexedDocument(DOC);
        String[] users = {"u1", "u2"};
        UUID[] sessions = new UUID[2];
        for (int i = 0; i < 2; i++) {
            operator(users[i]);
            sessions[i] = session(users[i], doc);
        }
        int ok = 0;
        MvcResult last = null;
        for (int i = 0; i < 8; i++) {
            last = ask(users[i % 2], sessions[i % 2], "Que color tiene el cielo " + i);
            if (status(last) == 200) {
                ok++;
            }
        }
        assertThat(ok).isEqualTo(5);
        assertThat(status(last)).isEqualTo(429);
        assertThat(body(last).path("code").asText()).isEqualTo("CHAT_RATE_LIMITED");
        assertThat(retryAfter(last)).isNotNull();
    }

    @Test
    void topeDiarioDeTokensPorTenantDa429DeCuotaConRetryAfterHastaMedianocheUtc() throws Exception {
        UUID doc = indexedDocument(DOC);
        operator("ana");
        operator("beto");
        UUID sa = session("ana", doc);
        UUID sb = session("beto", doc);
        // Cada respuesta del LLM de prueba consume 2 tokens (1 + 1); tope 5: la tercera supera el tope.
        assertThat(status(ask("ana", sa, "Cual es el monto embargado uno"))).isEqualTo(200);
        assertThat(status(ask("beto", sb, "Cual es el monto embargado dos"))).isEqualTo(200);
        assertThat(status(ask("ana", sa, "Cual es el monto embargado tres"))).isEqualTo(200);
        assertThat(count("select (tokens_in + tokens_out)::int from chat_token_usage")).isEqualTo(6);
        int texts = embeddings.texts();
        int calls = llm.calls();

        MvcResult r = ask("beto", sb, "Cual es el monto embargado cuatro");

        assertThat(status(r)).isEqualTo(429);
        assertThat(body(r).path("code").asText()).isEqualTo("CHAT_QUOTA_EXCEEDED");
        assertThat(Long.parseLong(retryAfter(r))).isBetween(1L, 86_400L);
        assertThat(embeddings.texts()).isEqualTo(texts);
        assertThat(llm.calls()).isEqualTo(calls);
        // El tope es por tenant: otro tenant no se ve afectado.
        String before = tenant;
        tenant = newTenant();
        try {
            UUID d2 = indexedDocument(DOC);
            operator("ana");
            assertThat(status(ask("ana", session("ana", d2), "Cual es el monto embargado cinco"))).isEqualTo(200);
        } finally {
            tenant = before;
        }
    }

    @Test
    void timeoutRealHaciaElLlmDa503YCancelaLaLlamada() throws Exception {
        operator("ana");
        UUID s = session("ana", indexedDocument(DOC));
        AtomicBoolean interrupted = new AtomicBoolean();
        llm.respond(prompt -> {
            try {
                Thread.sleep(10_000);
            } catch (InterruptedException e) {
                interrupted.set(true);
            }
            return TestBeans.ScriptedLlm.reply("tarde");
        });
        long t0 = System.nanoTime();

        MvcResult r = ask("ana", s, "Cual es el monto embargado timeout");

        assertThat(status(r)).isEqualTo(503);
        assertThat(body(r).path("code").asText()).isEqualTo("CHAT_AI_UNAVAILABLE");
        assertThat((System.nanoTime() - t0) / 1_000_000).isLessThan(5_000);
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(5)).untilTrue(interrupted);
        assertThat(count("select count(*) from chat_message")).isZero();
    }
}
