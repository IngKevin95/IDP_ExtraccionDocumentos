package com.idp.extraction.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.idp.extraction.llm.ExtractionRun;
import com.idp.extraction.store.ExtractionRepository;
import com.idp.extraction.store.ExtractionRepository.AiRecord;
import com.idp.kms.KeyService;
import com.idp.tenant.TenantId;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

/**
 * Registro firmado e inmutable de la ejecucion de IA (SEC-049, SEC-036): version de prompt, huellas de los
 * prompts efectivamente enviados (no su contenido, que incluye datos del oficio), versiones de modelo,
 * calibracion y configuracion. La firma usa {@link KeyService} del tenant.
 */
public final class AiExecutionRegistry {

    private final KeyService keys;
    private final ExtractionRepository repository;
    private final ObjectMapper mapper;
    private final String signingKeyId;

    public AiExecutionRegistry(KeyService keys, ExtractionRepository repository, ObjectMapper mapper,
                               String signingKeyId) {
        this.keys = keys;
        this.repository = repository;
        this.mapper = mapper;
        this.signingKeyId = signingKeyId;
    }

    public record Config(String promptVersion, String typologyCode, Integer typologyVersion, String calibratorId,
                         double classificationMinConfidence, String routing) {
    }

    /** Firma y persiste el registro dentro de la transaccion de negocio en curso. */
    public AiRecord record(UUID tenantId, UUID documentId, UUID extractionId, ExtractionRun run, Config config,
                           Instant at) {
        ObjectNode payload = mapper.createObjectNode();
        payload.put("tenantId", tenantId.toString());
        payload.put("documentId", documentId.toString());
        payload.put("extractionId", extractionId.toString());
        payload.put("promptVersion", config.promptVersion());
        payload.put("typologyCode", config.typologyCode());
        if (config.typologyVersion() != null) {
            payload.put("typologyVersion", config.typologyVersion());
        }
        payload.put("calibrator", config.calibratorId());
        payload.put("classificationMinConfidence", config.classificationMinConfidence());
        payload.put("routing", config.routing());
        payload.put("recordedAt", at.toString());
        var models = payload.putArray("modelVersions");
        run.modelVersions().stream().sorted().forEach(models::add);
        var hashes = payload.putArray("promptHashes");
        run.promptHashes().forEach(hashes::add);
        String json = canonical(payload);
        byte[] signature = keys.sign(new TenantId(tenantId.toString()), json.getBytes(StandardCharsets.UTF_8),
            signingKeyId).getData();
        AiRecord record = new AiRecord(UUID.randomUUID(), extractionId, json,
            Base64.getEncoder().encodeToString(signature), signingKeyId);
        repository.saveAiRecord(tenantId, record);
        return record;
    }

    /** Verifica la firma de un registro persistido. */
    public boolean verify(UUID tenantId, AiRecord record) {
        return keys.verify(new TenantId(tenantId.toString()), record.payload().getBytes(StandardCharsets.UTF_8),
            Base64.getDecoder().decode(record.signature()), record.keyId());
    }

    private String canonical(ObjectNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("No se pudo serializar el registro de IA", e);
        }
    }
}
