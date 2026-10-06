package com.idp.review.web;

import com.idp.review.domain.Correction;
import com.idp.review.domain.ReviewField;
import com.idp.review.domain.ReviewTask;
import com.idp.review.infra.ReviewRepository.QueueItem;
import com.idp.review.infra.ReviewRepository.Stats;
import com.idp.review.service.ReviewQueryService.Page;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

/** DTOs de la API (contracts/openapi/review-service.yaml). */
public final class ReviewDtos {

    private ReviewDtos() {
    }

    public record TaskResponse(UUID id, UUID documentId, String status, String assigneeId, String firstReviewerId,
                               String secondReviewerId, boolean criticalCorrection, OffsetDateTime slaDueAt,
                               int escalationLevel, boolean overdue, OffsetDateTime createdAt,
                               OffsetDateTime updatedAt, boolean blindSample) {

        /** {@code showBlind=false} oculta blindSample (siempre false) para no romper la ceguera del revisor. */
        public static TaskResponse of(ReviewTask t, Clock clock, boolean showBlind) {
            OffsetDateTime now = OffsetDateTime.ofInstant(clock.instant(), java.time.ZoneOffset.UTC);
            return new TaskResponse(t.id(), t.documentId(), t.status().name(), t.assigneeId(), t.firstReviewerId(),
                    t.secondReviewerId(), t.criticalCorrection(), t.slaDueAt(), t.escalationLevel(), t.overdue(now),
                    t.createdAt(), t.updatedAt(), showBlind && t.blindSample());
        }
    }

    public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

        public static <S, T> PageResponse<T> of(Page<S> p, Function<S, T> map) {
            return new PageResponse<>(p.content().stream().map(map).toList(), p.page(), p.size(),
                    p.totalElements(), p.totalPages());
        }
    }

    public record CorrectionRequest(String fieldName, String originalValue, String correctedValue) {
    }

    public record CorrectionResponse(UUID id, String fieldName, String originalValue, String correctedValue,
                                     boolean isCritical, String createdBy, OffsetDateTime createdAt) {

        public static CorrectionResponse of(Correction c) {
            return new CorrectionResponse(c.id(), c.fieldName(), c.originalValue(), c.correctedValue(),
                    c.critical(), c.createdBy(), c.createdAt());
        }
    }

    public record FieldResponse(UUID id, UUID taskId, String fieldName, Integer page, BigDecimal confidence,
                                boolean critical, String status, boolean hasEvidence) {

        public static FieldResponse of(ReviewField f) {
            return new FieldResponse(f.id(), f.taskId(), f.fieldName(), f.page(), f.confidence(), f.critical(),
                    f.status().name(), f.page() != null && f.boundingBox() != null);
        }
    }

    public record QueueItemResponse(UUID fieldId, UUID taskId, UUID documentId, String fieldName, boolean critical,
                                    Integer page, BigDecimal confidence, String taskStatus, String assigneeId,
                                    OffsetDateTime slaDueAt, int escalationLevel) {

        public static QueueItemResponse of(QueueItem q) {
            return new QueueItemResponse(q.fieldId(), q.taskId(), q.documentId(), q.fieldName(), q.critical(),
                    q.page(), q.confidence(), q.taskStatus().name(), q.assigneeId(), q.slaDueAt(),
                    q.escalationLevel());
        }
    }

    public record ReassignRequest(String assigneeId) {
    }

    public record CropLinkResponse(String url, Instant expiresAt) {
    }

    public record MetricsResponse(Map<String, Long> tasksByStatus, long unassigned, long overdue, long escalated,
                                  Double avgQueueSeconds) {

        public static MetricsResponse of(Stats s) {
            Map<String, Long> byStatus = new java.util.LinkedHashMap<>();
            s.byStatus().forEach((k, v) -> byStatus.put(k.name(), v));
            return new MetricsResponse(byStatus, s.unassigned(), s.overdue(), s.escalated(), s.avgCycleSeconds());
        }
    }
}
