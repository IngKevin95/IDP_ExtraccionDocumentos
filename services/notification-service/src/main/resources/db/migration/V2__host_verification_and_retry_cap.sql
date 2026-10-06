-- Verificacion de propiedad de los hosts de la allowlist (SEC-027): un host nuevo solo se activa con prueba de
-- dominio (TXT DNS) o aprobacion de plataforma; el TENANT_ADMIN no se autoriza a si mismo.
create table webhook_host_verification (
    tenant_id   uuid         not null,
    domain      varchar(253) not null,
    token       varchar(64)  not null,
    status      varchar(16)  not null,
    created_at  timestamp with time zone not null,
    verified_at timestamp with time zone,
    primary key (tenant_id, domain)
);

-- Tope de reintentos manuales por entrega (POST .../retry no reinicia intentos sin limite).
alter table webhook_delivery add column manual_retries integer not null default 0;
