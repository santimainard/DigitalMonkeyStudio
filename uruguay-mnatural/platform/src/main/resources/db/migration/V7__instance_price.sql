alter table activity_instance add column unit_price numeric(12,2);
update activity_instance i set unit_price = a.unit_price from activity a where a.id = i.activity_id;
alter table activity_instance alter column unit_price set not null;
alter table activity_instance add constraint instance_unit_price_positive check (unit_price > 0);
