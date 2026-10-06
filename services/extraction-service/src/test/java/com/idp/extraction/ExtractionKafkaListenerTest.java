package com.idp.extraction;

import com.idp.testsupport.Topics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.idp.events.EventValidationException;
import com.idp.extraction.config.ExtractionKafkaListener;
import com.idp.extraction.store.ExtractionRepository.Status;
import com.idp.extraction.support.ExtractionTestEnv;
import com.idp.extraction.support.FakeLlm;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Adaptador primario Kafka: filtra por tipo de evento y delega en el consumidor idempotente (AC-09). */
class ExtractionKafkaListenerTest {

    private final FakeLlm llm = FakeLlm.cleanEc();
    private final ExtractionTestEnv env = ExtractionTestEnv.create(llm, FakeLlm.cleanEc());
    private final ExtractionKafkaListener listener =
        new ExtractionKafkaListener(env.consumer, env.processor, env.mapper,
            com.idp.events.EventOriginGuard.standalone());

    @Test
    void ac09_procesaElComandoYIgnoraLaRedeliveryDelMismoEvento() {
        UUID tenant = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        env.storage.putSinglePage(tenant, doc, ExtractionTestEnv.STANDARD_TEXT);
        String cmd = env.command(tenant, doc, "EC");

        Topics.deliver(listener::onMessage, cmd);
        int calls = llm.calls.get();
        Topics.deliver(listener::onMessage, cmd);

        assertThat(llm.calls.get()).isEqualTo(calls).isPositive();
        assertThat(env.inTenant(tenant, () -> env.repository.findByDocument(tenant, doc)).orElseThrow().status())
            .isEqualTo(Status.COMPLETED);
    }

    @Test
    void sec052_unComandoPorUnTopicoAjenoSeIgnora() {
        UUID tenant = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        env.storage.putSinglePage(tenant, doc, ExtractionTestEnv.STANDARD_TEXT);

        listener.onMessage(env.command(tenant, doc, "EC"), "extraction.events");

        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void ignoraEventosQueNoSonElComandoDeExtraccion() {
        Topics.deliver(listener::onMessage, "{\"eventType\":\"documento.recibido\",\"schemaVersion\":1}");

        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void unMensajeMalformadoEsUnErrorFatalParaElDlt() {
        assertThatThrownBy(() -> Topics.deliver(listener::onMessage, "no es json")).isInstanceOf(EventValidationException.class);
    }

    @Test
    void unComandoFueraDeContratoEsUnErrorFatalParaElDlt() {
        UUID tenant = UUID.randomUUID();
        String invalid = env.command(tenant, UUID.randomUUID(), "ec-minusculas");

        assertThatThrownBy(() -> Topics.deliver(listener::onMessage, invalid)).isInstanceOf(EventValidationException.class);
    }
}
