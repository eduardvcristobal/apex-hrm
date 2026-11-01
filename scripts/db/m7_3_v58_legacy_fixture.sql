\set ON_ERROR_STOP on
-- Synthetic-only V58 fixture: never run on a real HRM database.
INSERT INTO tenant(id,code,name,slug,display_name)
VALUES ('00000000-0000-4000-8000-0000000000d1','m73-fixture','M73 Fixture',
        'm73-fixture','M73 Fixture');
INSERT INTO company(id,tenant_id,legal_name,company_code)
VALUES ('00000000-0000-4000-8000-0000000000d2',
        '00000000-0000-4000-8000-0000000000d1','M73 Company','M73');
INSERT INTO employee(id,tenant_id,company_id,employee_no,first_name,last_name,hire_date)
VALUES ('00000000-0000-4000-8000-0000000000d3',
        '00000000-0000-4000-8000-0000000000d1',
        '00000000-0000-4000-8000-0000000000d2',
        'M73-001','Synthetic','Employee','2026-01-01');
INSERT INTO employee_bank_account(id,tenant_id,employee_id,bank_code,account_no,account_name)
VALUES ('00000000-0000-4000-8000-0000000000d4',
        '00000000-0000-4000-8000-0000000000d1',
        '00000000-0000-4000-8000-0000000000d3',
        'TESTBANK','000123456789','Synthetic Employee');
