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

    /**
     * Todos los campos extraidos de un documento ya aprobado (ultima extraccion), para la revision ciega: el revisor
     * los transcribe sin ver la salida del modelo. Vacio si no hay extraccion disponible.
     */
    default List<FieldCandidate> approvedFields(UUID documentId) {
        return List.of();
    }

    /** Estado de aprobacion del documento en el silo (tabla document del document-service). */
    record ApprovedDocument(String status, String approvedBy, String classification) {
    }

    /**
     * Estado, aprobador y clasificacion del documento en SU silo. Es la fuente de verdad para aceptar una muestra
     * ciega: el evento de calidad solo prueba que alguien lo publico (SEC-052). Vacio = no verificable (se rechaza).
     */
    default java.util.Optional<ApprovedDocument> approvedDocument(UUID documentId) {
        return java.util.Optional.empty();
    }

    /** Subject de quien cargo el documento, si el dato existe (para exigir un revisor ciego independiente). */
    default java.util.Optional<String> uploader(UUID documentId) {
        return java.util.Optional.empty();
    }
}
