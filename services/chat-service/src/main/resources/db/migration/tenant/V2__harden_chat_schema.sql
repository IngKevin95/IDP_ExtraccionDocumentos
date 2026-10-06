-- chat-service V2 (auditoria F6): contenido cifrado, trazabilidad del modelo, cuota diaria y purga explicita.
-- No edita V1: transforma el esquema de forma incremental.

-- SEC-018/SEC-015: el texto del oficio, de las preguntas/respuestas y de las citas se guarda cifrado con el sobre del
-- tenant (AES-GCM, DEK envuelta con la KEK de datos; AAD = tenantId|documentId|recordId|campo). Los embeddings siguen
-- en claro (riesgo residual documentado en la matriz de controles). Las columnas en claro de V1 se eliminan; una fila
-- legada sin cifrar queda ilegible (content_enc nulo) y se re-indexa: el servicio aun no tenia datos en produccion.
alter table chunk add column content_enc bytea;
alter table chunk drop column content;
alter table chunk add constraint chk_chunk_content_enc check (content_enc is not null) not valid;

alter table chat_message add column content_enc bytea;
alter table chat_message drop column content;
alter table chat_message add constraint chk_message_content_enc check (content_enc is not null) not valid;

alter table citation add column exact_quote_enc bytea;
alter table citation drop column exact_quote;
alter table citation add constraint chk_citation_quote_enc check (exact_quote_enc is not null) not valid;

-- SEC-049/SEC-036: version del modelo, del prompt y de la configuracion por respuesta, y tokens consumidos.
alter table chat_message add column model varchar(128);
alter table chat_message add column prompt_version varchar(32);
alter table chat_message add column config_hash varchar(64);
alter table chat_message add column tokens_in integer check (tokens_in >= 0);
alter table chat_message add column tokens_out integer check (tokens_out >= 0);

-- Cache semantica (SEC-048): solo se sirve si la pregunta normalizada es identica o si coinciden sus tokens
-- significativos (cifras, identificadores y nombres propios). Son huellas SHA-256, nunca el texto.
alter table chat_message add column question_hash varchar(64);
alter table chat_message add column question_sig varchar(64);
create index idx_chat_message_qhash on chat_message (question_hash) where question_hash is not null;

-- Tope diario de tokens del LLM por tenant (cada silo es de un tenant). Dia UTC.
create table chat_token_usage (
    day        date    primary key,
    tokens_in  bigint  not null default 0 check (tokens_in >= 0),
    tokens_out bigint  not null default 0 check (tokens_out >= 0)
);

-- SEC-022: la inmutabilidad admite SOLO la purga explicita de un documento (Habeas Data, documento.purgado). El
-- consumidor fija en su transaccion {idp.purge_document_id} = documentId (set_config local); el trigger permite
-- DELETE unicamente de filas de ESE documento. UPDATE y TRUNCATE siguen prohibidos siempre (los triggers de
-- TRUNCATE y reject_mutation de V1 no cambian). Una funcion por tabla: PL/pgSQL no admite referirse a columnas de
-- OLD que la otra tabla no tiene.
create function guard_chat_message_mutation() returns trigger as $$
declare
    purge text := current_setting('idp.purge_document_id', true);
begin
    if tg_op = 'DELETE' and purge is not null and purge <> '' and exists (
            select 1 from chat_session s where s.id = old.session_id and s.document_id::text = purge) then
        return old;
    end if;
    raise exception 'chat_message y citation son inmutables (%)', tg_op;
end;
$$ language plpgsql;

create function guard_citation_mutation() returns trigger as $$
declare
    purge text := current_setting('idp.purge_document_id', true);
begin
    if tg_op = 'DELETE' and purge is not null and purge <> '' and exists (
            select 1 from chat_message m join chat_session s on s.id = m.session_id
            where m.id = old.message_id and s.document_id::text = purge) then
        return old;
    end if;
    raise exception 'chat_message y citation son inmutables (%)', tg_op;
end;
$$ language plpgsql;

drop trigger trg_chat_message_immutable on chat_message;
create trigger trg_chat_message_immutable before update or delete on chat_message
    for each row execute function guard_chat_message_mutation();
drop trigger trg_citation_immutable on citation;
create trigger trg_citation_immutable before update or delete on citation
    for each row execute function guard_citation_mutation();
