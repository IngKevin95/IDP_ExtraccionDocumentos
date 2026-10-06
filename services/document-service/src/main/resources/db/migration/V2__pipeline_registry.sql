-- SEC-052: el document-service solo cambia el estado por eventos del pipeline que puede contrastar con lo que el
-- mismo registro: la solicitud de extraccion que emitio, la solicitud de revision que registro al consumir
-- extraccion.requiere_revision y quien aprobo el documento (lo lee el review-service para aceptar muestras ciegas).

alter table document add column approved_by varchar(32);

create table extraction_request (
    document_id  uuid primary key references document (id) on delete cascade,
    requested_at timestamp with time zone not null
);

create table review_request (
    task_id      uuid primary key,
    document_id  uuid not null references document (id) on delete cascade,
    requested_at timestamp with time zone not null
);

create index idx_review_request_document on review_request (document_id);

-- Documentos ya en extraccion al desplegar: su solicitud existio aunque no se registro.
insert into extraction_request (document_id, requested_at)
select id, updated_at from document where status = 'EN_EXTRACCION' and purged_at is null;
