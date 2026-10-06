package com.idp.chat.service;

import java.util.UUID;

/**
 * Puerto de autorizacion por documento (SEC-007). Se invoca al crear la sesion, en cada mensaje (antes de tocar el
 * indice vectorial) y al indexar. La implementacion consulta el silo del tenant del contexto.
 */
public interface DocumentAccessChecker {

    /**
     * @throws Exceptions.DocumentNotFoundException si el documento no existe en el tenant o fue purgado
     * @throws Exceptions.AccessDeniedException si el llamador no puede ver el documento (clasificacion)
     */
    void assertCanRead(Caller caller, UUID documentId);

    /** El documento existe en el silo del tenant del contexto y no fue purgado: unica condicion para indexarlo. */
    boolean isIndexable(UUID documentId);
}
