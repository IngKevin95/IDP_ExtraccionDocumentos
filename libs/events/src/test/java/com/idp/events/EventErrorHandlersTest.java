package com.idp.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.kafka.support.SendResult;

@SuppressWarnings({"unchecked", "rawtypes"})
class EventErrorHandlersTest {

    @Test
    void ac08_errorFatalVaDirectoAlDltSinReintentos() {
        KafkaOperations ops = mock(KafkaOperations.class);
        when(ops.send(any(ProducerRecord.class))).thenReturn(CompletableFuture.<SendResult>completedFuture(null));
        when(ops.partitionsFor(any())).thenReturn(List.of());
        DefaultErrorHandler handler = EventErrorHandlers.deadLetter(ops);
        assertNotNull(handler);
        ConsumerRecord<String, String> rec = new ConsumerRecord<>("acceso.revocado", 0, 5L, "k", "{}");

        handler.handleOne(new RuntimeException("wrap", new EventValidationException("malformado")), rec,
            mock(Consumer.class), mock(MessageListenerContainer.class));

        ArgumentCaptor<ProducerRecord> cap = ArgumentCaptor.forClass(ProducerRecord.class);
        verify(ops).send(cap.capture());
        assertEquals("acceso.revocado-dlt", cap.getValue().topic());
    }
}
