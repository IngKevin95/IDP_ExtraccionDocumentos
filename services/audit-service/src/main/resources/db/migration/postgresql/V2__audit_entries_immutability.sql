-- Inmutabilidad de audit_entries (regla 3 de la spec): sin DELETE ni TRUNCATE; el unico UPDATE permitido es
-- marcar worm_anchored false -> true. Solo PostgreSQL (los tests en H2 cubren la deteccion por hash-chain).

create function audit_entries_guard() returns trigger as $$
begin
    if tg_op = 'DELETE' then
        raise exception 'audit_entries es inmutable: DELETE prohibido';
    end if;
    if (new.id, new.sequence_id, new.tenant_id, new.event_id, new.correlation_id, new.document_id,
        new.event_type, new.actor_id, new.occurred_at, new.payload::text, new.payload_sha256,
        new.current_hash, new.previous_hash, new.created_at)
       is distinct from
       (old.id, old.sequence_id, old.tenant_id, old.event_id, old.correlation_id, old.document_id,
        old.event_type, old.actor_id, old.occurred_at, old.payload::text, old.payload_sha256,
        old.current_hash, old.previous_hash, old.created_at) then
        raise exception 'audit_entries es inmutable: UPDATE prohibido';
    end if;
    if old.worm_anchored and not new.worm_anchored then
        raise exception 'audit_entries es inmutable: worm_anchored no puede revertirse';
    end if;
    return new;
end;
$$ language plpgsql;

create trigger trg_audit_entries_guard
    before update or delete on audit_entries
    for each row execute function audit_entries_guard();

create function audit_entries_no_truncate() returns trigger as $$
begin
    raise exception 'audit_entries es inmutable: TRUNCATE prohibido';
end;
$$ language plpgsql;

create trigger trg_audit_entries_no_truncate
    before truncate on audit_entries
    for each statement execute function audit_entries_no_truncate();
