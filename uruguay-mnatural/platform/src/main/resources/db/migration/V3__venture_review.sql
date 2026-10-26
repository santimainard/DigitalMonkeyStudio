alter table venture add column denial_reason varchar(2000);
alter table venture add column allow_reapply boolean not null default false;
alter table venture add column reviewed_by bigint references app_user(id);
alter table venture add column reviewed_at timestamp with time zone;
