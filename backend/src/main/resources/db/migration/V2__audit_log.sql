-- Журнал аудита. V1 не изменяется: его контрольная сумма уже применена.
create table audit_log (
    id          bigserial   primary key,
    ts          timestamptz not null default now(),
    actor_email varchar,
    actor_role  varchar,
    event       varchar     not null,
    ip          varchar(45),
    user_agent  varchar(256),
    target      varchar(256),
    details     jsonb,
    success     boolean
);

create index idx_audit_log_ts on audit_log (ts desc);
create index idx_audit_log_event_ts on audit_log (event, ts desc);
