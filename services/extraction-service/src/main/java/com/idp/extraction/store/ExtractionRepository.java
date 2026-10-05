package com.idp.extraction.store;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Persistencia de extracciones en el silo del tenant. */
public interface ExtractionRepository {

    /** Estados finales de una extraccion. */
    final class Status {
        public static final String COMPLETED = "COMPLETED";
        public static final String REQUIRES_REVIEW = "REQUIRES_REVIEW";
        public static final String ABORTED_INJECTION = "ABORTED_INJECTION";
        public static final String UNSUPPORTED = "UNSUPPORTED_TYPOLOGY";
        public static final String NO_OFICIO = "NO_OFICIO";
        public static final String CLASSIFICATION_REVIEW = "CLASSIFICATION_REVIEW";

        private Status() {
        }
    }

    record ExtractionRecord(UUID id, UUID documentId, UUID tenantId, String status, String typologyCode,
                            Integer typologyVersion, String modelVersion, String promptVersion,
                            BigDecimal overallScore, int tokensIn, int tokensOut, BigDecimal costUsd,
                            UUID reviewTaskId, String detail, Instant createdAt) {
    }

    record FieldRecord(UUID id, String tableName, Integer rowIndex, String fieldName, String valueText,
                       BigDecimal confidence, Integer evidencePage, String evidenceQuote, String boundingBoxJson,
                       boolean requiresReview, String validationError) {
    }

    record AiRecord(UUID id, UUID extractionId, String payload, String signature, String keyId) {
    }

    /** True si ya hay una extraccion terminada (cualquier estado salvo el aborto por injection) del documento. */
    boolean existsFinished(UUID documentId);

    void save(ExtractionRecord extraction, List<FieldRecord> fields);

    void saveAiRecord(UUID tenantId, AiRecord record);

    Optional<ExtractionRecord> findByDocument(UUID tenantId, UUID documentId);

    List<FieldRecord> fields(UUID tenantId, UUID extractionId);

    Optional<AiRecord> aiRecord(UUID tenantId, UUID extractionId);
}
