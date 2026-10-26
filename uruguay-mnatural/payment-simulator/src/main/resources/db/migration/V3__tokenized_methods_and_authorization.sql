-- No PAN or CVV columns: a rule code is enough for these explicitly fictitious methods.
create table simulated_method(
 token uuid primary key,registration_reference varchar(100) not null unique,
 customer_reference varchar(100) not null,brand varchar(30) not null,last_four varchar(4) not null,
 holder varchar(100) not null,expiry_month integer not null check(expiry_month between 1 and 12),
 expiry_year integer not null,rule_code varchar(30) not null,created_at timestamp with time zone not null default current_timestamp);
alter table simulated_payment add column reservation_reference varchar(100);
update simulated_payment set reservation_reference=merchant_reference;
alter table simulated_payment alter column reservation_reference set not null;
alter table simulated_payment add column method_token uuid references simulated_method(token);
alter table simulated_payment add column requested_method_token uuid;
alter table simulated_payment add column customer_reference varchar(100);
alter table simulated_payment add column decline_reason varchar(80);
create index simulated_payment_reservation on simulated_payment(reservation_reference,status);

-- An upgrade must not retain an old manual failure-injection setting.
update simulator_setting set mode='NORMAL' where id=1;
