package com.idp.review.service;

import com.idp.review.domain.Correction;
import com.idp.review.domain.ReviewField;
import com.idp.review.domain.ReviewTask;
import com.idp.review.domain.TaskStatus;
import com.idp.review.infra.ReviewRepository;
import com.idp.review.infra.ReviewRepository.QueueItem;
import com.idp.review.infra.ReviewRepository.Scope;
import com.idp.review.infra.ReviewRepository.Stats;
import com.idp.review.service.Exceptions.TaskNotFoundException;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Consultas de la cola, las tareas y las metricas. 404 estricto para tareas de otro tenant (SEC-003, AC-08). */
@Service
public class ReviewQueryService {

    public record Page<T>(List<T> content, int page, int size, long totalElements, int totalPages) {
    }

    private final ReviewRepository repo;
    private final Clock clock;

    public ReviewQueryService(ReviewRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    public ReviewTask get(Caller caller, UUID taskId) {
        return repo.findTask(caller.tenantId(), taskId).orElseThrow(TaskNotFoundException::new);
    }

    public Page<ReviewTask> list(Caller caller, TaskStatus status, boolean mine, boolean escalatedOnly, int page,
                                 int size) {
        int s = clampSize(size);
        int p = Math.max(page, 0);
        String assignee = mine ? caller.userId() : null;
        long total = repo.countTasks(caller.tenantId(), status, assignee, escalatedOnly);
        List<ReviewTask> data = repo.listTasks(caller.tenantId(), status, assignee, escalatedOnly, s, p * s);
        return new Page<>(data, p, s, total, pages(total, s));
    }

    public List<ReviewField> fields(Caller caller, UUID taskId) {
        get(caller, taskId);
        return repo.fields(taskId);
    }

    public List<Correction> corrections(Caller caller, UUID taskId) {
        get(caller, taskId);
        return repo.corrections(taskId);
    }

    /** Cola por campo: campos pendientes de tareas abiertas; escaladas y mas proximas a vencer primero. */
    public Page<QueueItem> queue(Caller caller, Scope scope, int page, int size) {
        int s = clampSize(size);
        int p = Math.max(page, 0);
        long total = repo.countQueue(caller.tenantId(), scope, caller.userId());
        return new Page<>(repo.queue(caller.tenantId(), scope, caller.userId(), s, p * s), p, s, total,
                pages(total, s));
    }

    public Stats metrics(Caller caller) {
        return repo.stats(caller.tenantId(), OffsetDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    private static int pages(long total, int size) {
        return (int) ((total + size - 1) / size);
    }
}
