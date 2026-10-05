package com.idp.review.infra;

import com.idp.review.domain.FieldCandidate;
import java.util.List;
import java.util.UUID;

/**
 * Puerto: campos dudosos que dieron origen a una tarea. El evento extraccion.requiere_revision es claim-check (solo
 * IDs), asi que los campos se resuelven en el silo del tenant, con el contexto de tenant del evento ya fijado.
 */
public interface FieldCandidateSource {

    /** Fuente vacia: la tarea se crea sin campos y el revisor los agrega al corregir. */
    FieldCandidateSource NONE = (documentId, taskId) -> List.of();

    List<FieldCandidate> candidates(UUID documentId, UUID taskId);
}
