package com.idp.extraction;

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
        new ExtractionKafkaListener(env.consumer, env.processor, env.mapper);

    @Test
    void ac09_procesaElComandoYIgnoraLaRedeliveryDelMismoEvento() {
        UUID tenant = UUID.randomUUID();
        UUID doc = UUID.randomUUID();
        env.storage.putSinglePage(tenant, doc, ExtractionTestEnv.STANDARD_TEXT);
        String cmd = env.command(tenant, doc, "EC");

        listener.onMessage(cmd);
        int calls = llm.calls.get();
        listener.onMessage(cmd);

        assertThat(llm.calls.get()).isEqualTo(calls).isPositive();
        assertThat(env.inTenant(tenant, () -> env.repository.findByDocument(tenant, doc)).orElseThrow().status())
            .isEqualTo(Status.COMPLETED);
    }

    @Test
    void ignoraEventosQueNoSonElComandoDeExtraccion() {
        listener.onMessage("{\"eventType\":\"documento.recibido\",\"schemaVersion\":1}");

        assertThat(llm.calls.get()).isZero();
    }

    @Test
    void unMensajeMalformadoEsUnErrorFatalParaElDlt() {
        assertThatThrownBy(() -> listener.onMessage("no es json")).isInstanceOf(EventValidationException.class);
    }

    @Test
    void unComandoFueraDeContratoEsUnErrorFatalParaElDlt() {
        UUID tenant = UUID.randomUUID();
        String invalid = env.command(tenant, UUID.randomUUID(), "ec-minusculas");

        assertThatThrownBy(() -> listener.onMessage(invalid)).isInstanceOf(EventValidationException.class);
    }
}
