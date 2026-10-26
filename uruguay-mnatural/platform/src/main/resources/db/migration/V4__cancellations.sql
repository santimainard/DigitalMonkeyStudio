alter table activity add column full_refund_minutes integer not null default 0 check(full_refund_minutes between 0 and 525600);
alter table activity add column late_refund_percent integer not null default 100 check(late_refund_percent between 0 and 100);
alter table reservation add column full_refund_minutes integer not null default 0;
alter table reservation add column late_refund_percent integer not null default 100;
alter table activity_instance add column cancelled boolean not null default false;
create table cancellation (
 reservation_id uuid primary key references reservation(id),
 requested_by varchar(20) not null check(requested_by in ('TOURIST','PROVIDER')),
 refund_percent integer not null check(refund_percent between 0 and 100),
 requested_at timestamp with time zone not null,
 completed_at timestamp with time zone,
 refunded_amount numeric(16,2),
 check(refunded_amount is null or refunded_amount>=0));
