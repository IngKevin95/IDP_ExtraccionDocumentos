-- Solo tests: tenants pertenece a tenant-service en la base de control compartida (A1: tenant ACTIVE).
create table if not exists tenants (
    id uuid primary key,
    status varchar(50) not null
);
