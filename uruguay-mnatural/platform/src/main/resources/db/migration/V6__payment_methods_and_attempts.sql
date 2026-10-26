-- Keep payment as the historical per-reservation summary; attempts are immutable identities.
create table payment_method(
 id uuid primary key,tourist_id bigint not null references app_user(id),
 external_token uuid not null unique,brand varchar(30) not null,last_four varchar(4) not null,
 holder varchar(100) not null,expiry_month integer not null check(expiry_month between 1 and 12),
 expiry_year integer not null,active boolean not null default true,
 created_at timestamp with time zone not null default current_timestamp);
create table payment_attempt(
 id uuid primary key,reservation_id uuid not null references reservation(id),
 method_id uuid references payment_method(id),client_key varchar(100) not null,
 merchant_reference varchar(100) not null unique,external_payment_id varchar(100) unique,
 status varchar(20) not null,amount numeric(16,2) not null,currency varchar(3) not null,
 expires_at timestamp with time zone not null,approved_at timestamp with time zone,
 created_at timestamp with time zone not null,last_checked_at timestamp with time zone,
 decline_reason varchar(80),legacy boolean not null default false,
 approved_reservation_id uuid unique references reservation(id),
 unique(reservation_id,client_key),
 check((status in ('APPROVED','REFUNDED') and approved_reservation_id is not null and approved_reservation_id=reservation_id)
    or (status not in ('APPROVED','REFUNDED') and approved_reservation_id is null)));
insert into payment_attempt(id,reservation_id,client_key,merchant_reference,external_payment_id,status,amount,currency,expires_at,approved_at,created_at,last_checked_at,legacy,approved_reservation_id)
 select p.id,p.reservation_id,'legacy',p.merchant_reference,p.external_payment_id,p.status,p.amount,p.currency,r.expires_at,p.approved_at,r.created_at,p.last_checked_at,true,
 case when p.status in ('APPROVED','REFUNDED') then p.reservation_id else null end
 from payment p join reservation r on r.id=p.reservation_id;
alter table payment add column current_attempt_id uuid references payment_attempt(id);
update payment set current_attempt_id=id;
alter table payment_event add column attempt_id uuid references payment_attempt(id);
update payment_event set attempt_id=(select a.id from payment_attempt a where a.external_payment_id=payment_event.payment_id);
create index payment_attempt_reservation on payment_attempt(reservation_id,created_at);
create index payment_attempt_status on payment_attempt(status,last_checked_at);
create index payment_method_owner on payment_method(tourist_id,active);
