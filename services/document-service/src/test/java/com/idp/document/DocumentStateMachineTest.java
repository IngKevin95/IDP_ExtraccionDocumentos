package com.idp.document;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.idp.document.domain.Classification;
import com.idp.document.domain.DocumentRecord;
import com.idp.document.domain.DocumentStatus;
import com.idp.document.domain.InvalidTransitionException;
import com.idp.document.infra.DocumentRepository;
import com.idp.document.service.DocumentStateMachine;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DocumentStateMachineTest {

    private final DocumentRepository repo = mock(DocumentRepository.class);
    private final DocumentStateMachine machine =
            new DocumentStateMachine(repo, new SimpleMeterRegistry(), Clock.systemUTC());

    private DocumentRecord doc(DocumentStatus status) {
        OffsetDateTime now = OffsetDateTime.now();
        return new DocumentRecord(UUID.randomUUID(), "t", "h", "EC", "r", 1, status, Classification.CONFIDENCIAL,
                "k", "application/pdf", 1L, "u", null, now, now, null);
    }

    @Test
    void ac05_flujoFelizCanonico() {
        assertThat(DocumentStatus.RECIBIDO.canTransitionTo(DocumentStatus.RENDERIZADO)).isTrue();
        assertThat(DocumentStatus.RENDERIZADO.canTransitionTo(DocumentStatus.EN_EXTRACCION)).isTrue();
        assertThat(DocumentStatus.EN_EXTRACCION.canTransitionTo(DocumentStatus.EN_REVISION)).isTrue();
        assertThat(DocumentStatus.EN_EXTRACCION.canTransitionTo(DocumentStatus.APROBADO)).isTrue();
        assertThat(DocumentStatus.EN_REVISION.canTransitionTo(DocumentStatus.APROBADO)).isTrue();
        assertThat(DocumentStatus.APROBADO_PENDIENTE_STEWARD.canTransitionTo(DocumentStatus.APROBADO)).isTrue();
    }

    @Test
    void regla2_aprobadoYTerminalesNoTienenSalida() {
        for (DocumentStatus terminal : new DocumentStatus[] {DocumentStatus.APROBADO, DocumentStatus.RECHAZADO,
                DocumentStatus.FALLIDO}) {
            assertThat(terminal.successors()).isEmpty();
        }
        assertThat(DocumentStatus.APROBADO.canTransitionTo(DocumentStatus.EN_REVISION)).isFalse();
        assertThat(DocumentStatus.RECIBIDO.canTransitionTo(DocumentStatus.APROBADO)).isFalse();
    }

    @Test
    void regla2_transicionInvalidaLanzaYNoTocaLaBd() {
        assertThatThrownBy(() -> machine.transition(doc(DocumentStatus.APROBADO), DocumentStatus.EN_REVISION))
                .isInstanceOf(InvalidTransitionException.class);
        verify(repo, never()).updateStatus(any(), any(), any(), any(), any());
    }

    @Test
    void transicionValidaActualizaConControlOptimista() {
        DocumentRecord d = doc(DocumentStatus.EN_EXTRACCION);
        when(repo.updateStatus(eq("t"), eq(d.id()), eq(DocumentStatus.EN_EXTRACCION),
                eq(DocumentStatus.EN_REVISION), any())).thenReturn(1, 0);

        assertThat(machine.transition(d, DocumentStatus.EN_REVISION).status()).isEqualTo(DocumentStatus.EN_REVISION);
        assertThatThrownBy(() -> machine.transition(d, DocumentStatus.EN_REVISION))
                .isInstanceOf(IllegalStateException.class);
    }
}
