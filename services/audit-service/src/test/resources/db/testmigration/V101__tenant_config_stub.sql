create table if not exists tenant_config (
    tenant_id varchar(36) primary key,
    data_kek_id varchar(64) not null,
    audit_kek_id varchar(64) not null
);
