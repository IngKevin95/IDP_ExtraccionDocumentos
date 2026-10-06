package com.idp.chat.service;

import com.idp.chat.infra.DocumentPurgeRepository;
import com.idp.chat.infra.DocumentPurgeRepository.Purged;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Purga de lo derivado de un documento purgado (documento.purgado, SEC-022, Habeas Data). Idempotente: purgar un
 * documento ya purgado o nunca indexado no borra nada. Debe invocarse dentro de la transaccion del consumidor
 * idempotente (el trigger de inmutabilidad lo exige: ver DocumentPurgeRepository).
 */
@Service
public class DocumentPurgeService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentPurgeService.class);

    private final DocumentPurgeRepository purges;

    public DocumentPurgeService(DocumentPurgeRepository purges) {
        this.purges = purges;
    }

    public Purged purge(UUID documentId) {
        Purged p = purges.purge(documentId);
        LOG.info("Documento {} purgado en chat: {} fragmentos, {} mensajes, {} citas, {} sesiones", documentId,
                p.chunks(), p.messages(), p.citations(), p.sessions());
        return p;
    }
}
