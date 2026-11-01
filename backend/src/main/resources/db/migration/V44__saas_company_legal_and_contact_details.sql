ALTER TABLE company
    ADD COLUMN entity_type varchar(40),
    ADD COLUMN registration_number varchar(100),
    ADD COLUMN owner_name varchar(200),
    ADD COLUMN owner_email varchar(254),
    ADD COLUMN owner_phone varchar(40),
    ADD COLUMN address_line1 varchar(200),
    ADD COLUMN address_line2 varchar(200),
    ADD COLUMN city varchar(120),
    ADD COLUMN region varchar(120),
    ADD COLUMN postal_code varchar(20),
    ADD COLUMN industry varchar(120);
