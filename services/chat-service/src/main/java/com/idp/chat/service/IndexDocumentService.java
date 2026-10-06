package com.idp.chat.service;

import com.idp.chat.config.ChatProperties;
import com.idp.chat.infra.ChunkRepository;
import com.idp.chat.infra.ChunkRepository.NewChunk;
import com.idp.chat.infra.SecurityEvents;
import com.idp.chat.infra.TextLayerSource;
import com.idp.chat.service.TextChunker.PageChunk;
import com.idp.events.EventValidationException;
import com.idp.llm.EmbeddingProvider;
import com.idp.tenant.TenantId;
import com.idp.tenant.context.TenantContextHolder;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Indexacion de un documento aprobado (AC-01) en dos fases para no retener una transaccion de base durante la
 * lectura del almacen ni las llamadas al proveedor de embeddings: {@link #prepare} (sin transaccion) produce los
 * fragmentos con sus vectores y {@link #persist} los guarda junto con el estado de indexacion en la transaccion del
 * consumidor idempotente. El tenant sale siempre del contexto fijado por el consumidor (nunca se genera uno).
 */
@Service
public class IndexDocumentService {

    private static final Logger LOG = LoggerFactory.getLogger(IndexDocumentService.class);

    private final TextLayerSource textLayer;
    private final EmbeddingProvider embeddings;
    private final ChunkRepository chunks;
    private final DocumentAccessChecker access;
    private final SecurityEvents events;
    private final ChatProperties props;
    private final TextChunker chunker;

    public IndexDocumentService(TextLayerSource textLayer, EmbeddingProvider embeddings, ChunkRepository chunks,
                                DocumentAccessChecker access, SecurityEvents events, ChatProperties props) {
        this.textLayer = textLayer;
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.access = access;
        this.events = events;
        this.props = props;
        this.chunker = new TextChunker(props.chunkMaxChars(), props.chunkOverlapChars());
    }

    /**
     * Fragmentos listos para guardar, o vacio si el documento ya esta indexado.
     *
     * @throws EventValidationException documento no indexable, sin texto o fuera de limites (error fatal, va al DLT)
     */
    public Optional<List<NewChunk>> prepare(UUID tenantId, UUID documentId) {
        String tenant = TenantContextHolder.getTenantId();
        if (tenant == null || !tenant.equals(tenantId.toString())) {
            throw new IllegalStateException("Contexto de tenant ausente o distinto al del evento");
        }
        if (!access.isIndexable(documentId)) {
            events.isolatedAccessDenied(tenantId, UUID.randomUUID(), documentId.toString(),
                    SecurityEvents.UNAUTHORIZED_TENANT);
            throw new EventValidationException("Documento no indexable en el tenant del evento");
        }
        if (chunks.isIndexed(documentId)) {
            LOG.debug("Documento {} ya indexado, evento ignorado", documentId);
            return Optional.empty();
        }
        List<TextChunker.Page> pages = textLayer.pages(tenant, documentId)
                .orElseThrow(() -> new IllegalStateException("Capa de texto no disponible"));
        List<PageChunk> pieces = chunker.chunk(pages);
        if (pieces.isEmpty()) {
            throw new EventValidationException("El documento no tiene texto indexable");
        }
        if (pieces.size() > props.maxChunksPerDocument()) {
            throw new EventValidationException("El documento excede el maximo de fragmentos");
        }
        List<NewChunk> out = new ArrayList<>(pieces.size());
        TenantId tid = new TenantId(tenant);
        int batch = props.embeddingBatchSize();
        for (int from = 0; from < pieces.size(); from += batch) {
            List<PageChunk> slice = pieces.subList(from, Math.min(from + batch, pieces.size()));
            List<float[]> vectors = embeddings.embedBatch(tid, slice.stream().map(PageChunk::content).toList());
            if (vectors.size() != slice.size()) {
                throw new IllegalStateException("El proveedor devolvio un numero inesperado de embeddings");
            }
            for (int i = 0; i < slice.size(); i++) {
                float[] v = vectors.get(i);
                if (v.length != props.embeddingDimension()) {
                    throw new IllegalStateException("Dimension de embedding " + v.length + " distinta de la "
                            + "configurada " + props.embeddingDimension());
                }
                out.add(new NewChunk(UUID.randomUUID(), from + i, slice.get(i).pageNumber(), slice.get(i).content(), v));
            }
        }
        return Optional.of(out);
    }

    /** Guarda estado y fragmentos de forma atomica. Se ejecuta en la transaccion del consumidor. */
    public void persist(UUID documentId, List<NewChunk> prepared) {
        if (chunks.insertIndexed(documentId, prepared)) {
            LOG.info("Documento {} indexado con {} fragmentos", documentId, prepared.size());
        } else {
            LOG.debug("Documento {} indexado por otro consumidor", documentId);
        }
    }
}
