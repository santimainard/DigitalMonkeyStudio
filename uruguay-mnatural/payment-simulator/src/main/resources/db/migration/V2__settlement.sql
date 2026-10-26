alter table simulated_payment add column cancellation_percent integer check(cancellation_percent between 0 and 100);
alter table simulated_payment add column refunded_amount numeric(16,2);
