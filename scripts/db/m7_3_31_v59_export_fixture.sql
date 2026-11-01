\set ON_ERROR_STOP on
-- Disposable CI fixture created on Flyway V59 only.
INSERT INTO tenant(id,code,name,slug,display_name)
VALUES ('00000000-0000-4000-8000-0000000000e1','m7331','Export Fixture','m7331','Export Fixture');
INSERT INTO company(id,tenant_id,legal_name,company_code)
VALUES ('00000000-0000-4000-8000-0000000000e2',
        '00000000-0000-4000-8000-0000000000e1','Export Fixture Company','EXP');
INSERT INTO pay_group(id,tenant_id,company_id,code,name)
VALUES ('00000000-0000-4000-8000-0000000000e3',
        '00000000-0000-4000-8000-0000000000e1',
        '00000000-0000-4000-8000-0000000000e2','EXP','Export Payroll');
INSERT INTO pay_period(id,tenant_id,pay_group_id,start_date,end_date,pay_date)
VALUES ('00000000-0000-4000-8000-0000000000e4',
        '00000000-0000-4000-8000-0000000000e1',
        '00000000-0000-4000-8000-0000000000e3','2016-01-01','2016-01-15','2016-01-20');
INSERT INTO payroll_run(id,tenant_id,pay_period_id,pay_group_id,status,run_type,total_net)
VALUES ('00000000-0000-4000-8000-0000000000e5',
        '00000000-0000-4000-8000-0000000000e1',
        '00000000-0000-4000-8000-0000000000e4',
        '00000000-0000-4000-8000-0000000000e3','LOCKED','REGULAR',123.45);
INSERT INTO bank_template(id,tenant_id,bank_code,name,column_spec)
VALUES ('00000000-0000-4000-8000-0000000000e6',
        '00000000-0000-4000-8000-0000000000e1','TEST','Test export','ACCOUNT_NO,EMPLOYEE_NO,AMOUNT');
INSERT INTO bank_file(id,tenant_id,run_id,bank_template_id,file_name,content,record_count,total_amount)
VALUES ('00000000-0000-4000-8000-0000000000e7',
        '00000000-0000-4000-8000-0000000000e1',
        '00000000-0000-4000-8000-0000000000e5',
        '00000000-0000-4000-8000-0000000000e6',
        'TEST_payroll_2016-01-20.csv',
        E'ACCOUNT_NO,EMPLOYEE_NO,AMOUNT\n000123456789,M7331,123.45\n',1,123.45);
