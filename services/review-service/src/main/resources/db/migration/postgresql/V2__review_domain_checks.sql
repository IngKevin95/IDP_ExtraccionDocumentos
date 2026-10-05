-- Restricciones de dominio solo en PostgreSQL (locations db/migration/{vendor}).
alter table review_task add constraint chk_review_task_status
    check (status in ('PENDING', 'PENDING_SECOND_APPROVAL', 'APPROVED', 'REJECTED'));

-- SEC-009: defensa en profundidad, el segundo aprobador nunca es el primero.
alter table review_task add constraint chk_review_task_four_eyes
    check (second_reviewer_id is null or first_reviewer_id is null or second_reviewer_id <> first_reviewer_id);

-- Un segundo aprobador solo existe si hubo correccion critica y la tarea quedo aprobada.
alter table review_task add constraint chk_review_task_second_requires_critical
    check (second_reviewer_id is null or (critical_correction and status = 'APPROVED'));

alter table review_field add constraint chk_review_field_status
    check (status in ('PENDING', 'CORRECTED', 'CONFIRMED'));
