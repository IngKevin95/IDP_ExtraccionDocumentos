-- audit-service: cadena de hash por tenant, anclajes WORM y legal hold (base de control).
-- Portable a PostgreSQL y H2 (modo PostgreSQL).

create table audit_entries (
    id             uuid primary key,
    sequence_id    bigint       not null,
    tenant_id      uuid         not null,
    event_id       uuid         not null,
    correlation_id uuid,
    document_id    uuid,
    event_type     varchar(100) not null,
    actor_id       varchar(100),
    occurred_at    timestamp with time zone not null,
    payload        jsonb        not null,
    payload_sha256 varchar(64)  not null,
    current_hash   varchar(64)  not null,
    previous_hash  varchar(64)  not null,
    worm_anchored  boolean      not null default false,
    created_at     timestamp with time zone not null
);
create unique index idx_audit_entries_tenant_seq on audit_entries (tenant_id, sequence_id);
create unique index idx_audit_entries_tenant_event on audit_entries (tenant_id, event_id);
create index idx_audit_entries_tenant_doc on audit_entries (tenant_id, document_id);
create index idx_audit_entries_tenant_worm on audit_entries (tenant_id, worm_anchored);

-- Cabeza de cadena: fila bloqueada (select for update) para serializar la ingesta por tenant.
create table audit_chain_head (
    tenant_id        uuid primary key,
    last_sequence_id bigint      not null,
    last_hash        varchar(64) not null
);

create table worm_anchors (
    anchor_id         uuid primary key,
    tenant_id         uuid         not null,
    start_sequence_id bigint       not null,
    end_sequence_id   bigint       not null,
    file_uri          varchar(500) not null,
    manifest_hash     varchar(64)  not null,
    signature         text         not null,
    created_at        timestamp with time zone not null
);
create index idx_worm_anchors_tenant on worm_anchors (tenant_id, start_sequence_id);

create table if not exists legal_hold_records (
    id          uuid primary key,
    tenant_id   uuid         not null,
    document_id uuid,
    reason      text         not null,
    applied_by  varchar(100) not null,
    status      varchar(30)  not null,
    created_at  timestamp with time zone not null,
    released_by varchar(100),
    released_at timestamp with time zone
);
create index if not exists idx_legal_hold_tenant_doc on legal_hold_records (tenant_id, document_id);

-- Idempotencia del consumidor (libs/events IdempotentEventConsumer).
create table processed_event (
    tenant_id    uuid not null,
    event_id     uuid not null,
    processed_at timestamp with time zone not null default current_timestamp,
    primary key (tenant_id, event_id)
);
