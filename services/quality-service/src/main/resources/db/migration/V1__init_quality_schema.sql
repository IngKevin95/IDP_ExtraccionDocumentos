-- quality-service: base de control (esquema propio). Sin PII: solo contadores, nombres de campo y metadatos.

-- Idempotencia de consumo (libs/events, IdempotentEventConsumer).
create table processed_event (
    tenant_id    uuid not null,
    event_id     uuid not null,
    processed_at timestamp with time zone not null default now(),
    primary key (tenant_id, event_id)
);

-- Agregados diarios por tenant y tipologia (STP, HITL, muestreo ciego, error silente).
create table qa_metrics_daily (
    tenant_id           uuid not null,
    fecha               date not null,
    tipologia           varchar(10) not null,
    total_documentos    integer not null default 0,
    stp_count           integer not null default 0,
    hitl_count          integer not null default 0,
    silent_error_count  integer not null default 0,
    blind_samples_total integer not null default 0,
    updated_at          timestamp with time zone not null default now(),
    primary key (tenant_id, fecha, tipologia)
);

-- Frecuencia de correccion por campo. origen: HITL (revision normal) o BLIND (muestreo ciego).
create table qa_field_error (
    tenant_id        uuid not null,
    fecha            date not null,
    tipologia        varchar(10) not null,
    campo_modificado varchar(64) not null,
    tipo_correccion  varchar(16) not null,
    origen           varchar(8) not null,
    cuenta           integer not null default 0,
    primary key (tenant_id, fecha, tipologia, campo_modificado, tipo_correccion, origen)
);

-- Costo y latencia por extraccion (sin identificador de documento) para p95 con muestra minima.
create table qa_extraction_sample (
    id               uuid primary key,
    tenant_id        uuid not null,
    fecha            date not null,
    tipologia        varchar(10) not null,
    model_prompt_key varchar(120),
    latency_ms       integer,
    cost_micros      bigint
);
create index idx_qa_extraction_sample on qa_extraction_sample (tenant_id, fecha, tipologia);

-- Oficios auto-aprobados elegidos para revision ciega. document_id es un identificador opaco.
create table qa_blind_sample (
    tenant_id   uuid not null,
    document_id uuid not null,
    tipologia   varchar(10) not null,
    selected_at timestamp with time zone not null default now(),
    status      varchar(10) not null default 'PENDING',
    primary key (tenant_id, document_id)
);

-- Alertas de deriva persistidas por el scheduler.
create table qa_drift_alert (
    tenant_id  uuid not null,
    fecha      date not null,
    kind       varchar(20) not null,
    observed   double precision not null,
    baseline   double precision not null,
    created_at timestamp with time zone not null default now(),
    primary key (tenant_id, fecha, kind)
);

-- Golden set: solo oficios sinteticos (RN-07, SEC-035). Por tenant.
create table golden_set_document (
    id                     uuid primary key,
    tenant_id              uuid not null,
    external_id            varchar(100),
    nombre                 varchar(255) not null,
    tipologia              varchar(10) not null,
    tags                   varchar(500),
    payload_sintetico_json text not null,
    created_at             timestamp with time zone not null default now(),
    constraint uk_golden_external unique (tenant_id, external_id)
);
create index idx_golden_tenant on golden_set_document (tenant_id, tipologia);

-- Corridas de evaluacion y replay. kind: EVALUATION | REPLAY. status: QUEUED | RUNNING | DONE | FAILED.
create table golden_set_evaluation (
    id               uuid primary key,
    tenant_id        uuid not null,
    kind             varchar(12) not null,
    status           varchar(10) not null,
    fecha            timestamp with time zone not null default now(),
    version_prompt   varchar(120) not null,
    accuracy         double precision,
    precision_micro  double precision,
    recall           double precision,
    f1               double precision,
    ece_raw          double precision,
    ece_calibrated   double precision,
    samples          integer,
    error            varchar(300),
    result_json      text
);
create index idx_golden_eval_tenant on golden_set_evaluation (tenant_id, fecha);

-- Calibracion (isotonica) por evaluacion.
create table qa_calibration (
    evaluation_id    uuid primary key,
    tenant_id        uuid not null,
    model_prompt_key varchar(120) not null,
    model_json       text not null,
    created_at       timestamp with time zone not null default now()
);
create index idx_qa_calibration_key on qa_calibration (tenant_id, model_prompt_key, created_at);

-- Umbrales vigentes por par modelo+prompt, tipologia y campo.
create table qa_threshold (
    tenant_id        uuid not null,
    model_prompt_key varchar(120) not null,
    tipologia        varchar(10) not null,
    campo            varchar(64) not null,
    scope            varchar(10) not null,
    tau_auto         double precision not null,
    tau_revisar      double precision not null,
    target_precision double precision not null,
    samples          integer not null,
    attainable       boolean not null,
    evaluation_id    uuid not null,
    created_at       timestamp with time zone not null default now(),
    primary key (tenant_id, model_prompt_key, tipologia, campo)
);
