-- M3.1.21 synthetic, positive source-complete path; CI ephemeral PostgreSQL ONLY.
-- Does not represent a realistic legally certified final pay or bank event.
BEGIN;
SELECT set_config('app.tenant_id','81000000-0000-4000-8000-000000000001',true);
INSERT INTO auth_identity(id,tenant_id,email,password_hash) VALUES
 ('ab000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','ci-f@example.invalid','unused'),
 ('ab000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000001','ci-g@example.invalid','unused'),
 ('ab000000-0000-4000-8000-000000000003','81000000-0000-4000-8000-000000000001','ci-h@example.invalid','unused');
INSERT INTO auth_identity_role(identity_id,role) VALUES
 ('ab000000-0000-4000-8000-000000000001','PAYROLL_OFFICER'),
 ('ab000000-0000-4000-8000-000000000002','PAYROLL_OFFICER'),
 ('ab000000-0000-4000-8000-000000000003','PAYROLL_OFFICER');
INSERT INTO auth_api_token(identity_id,token_sha256,expires_at,mfa_verified) VALUES
 ('ab000000-0000-4000-8000-000000000001',encode(sha256(convert_to(repeat('f',64),'UTF8')),'hex'),now()+interval '2 hours',true),
 ('ab000000-0000-4000-8000-000000000002',encode(sha256(convert_to(repeat('g',64),'UTF8')),'hex'),now()+interval '2 hours',true),
 ('ab000000-0000-4000-8000-000000000003',encode(sha256(convert_to(repeat('h',64),'UTF8')),'hex'),now()+interval '2 hours',true);
INSERT INTO personnel_employee(id,tenant_id,company_id,employee_number,first_name,last_name,hire_date,employment_status)
VALUES ('a1000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '82000000-0000-4000-8000-000000000001','CI-FP-POSITIVE','Synthetic','Tester','2026-01-01','TERMINATED');
INSERT INTO employee(id,tenant_id,company_id,employee_no,first_name,last_name,hire_date,status)
VALUES ('a2000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '82000000-0000-4000-8000-000000000001','CI-FP-POSITIVE','Synthetic','Tester','2026-01-01','ACTIVE');
INSERT INTO personnel_hr_case(id,tenant_id,employee_id,case_type,status,effective_date,reason,requested_by,decided_by,decided_at)
VALUES('a3000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 'a1000000-0000-4000-8000-000000000001','SEPARATION','APPROVED','2026-01-07',
 'CI source coverage simulation',
 '83000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000002',now());
INSERT INTO personnel_final_pay_handoff(id,tenant_id,employee_id,hr_case_id,created_by)
VALUES('a4000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 'a1000000-0000-4000-8000-000000000001',
 'a3000000-0000-4000-8000-000000000001',
 '83000000-0000-4000-8000-000000000001');
INSERT INTO payroll_final_pay_settlement(
 id,tenant_id,handoff_id,employee_id,case_id,status,
 unpaid_basic,accrued_thirteenth_month,unused_leave_conversion,separation_pay,
 other_earnings,outstanding_loans,accountable_property,withholding_tax,
 other_authorized_deductions,gross,deductions,net,accounting_reference,justification,
 requested_by,approved_by,approved_at)
VALUES('a5000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 'a4000000-0000-4000-8000-000000000001','a1000000-0000-4000-8000-000000000001',
 'a3000000-0000-4000-8000-000000000001','APPROVED',
 1000,0,0,0,0,0,0,0,0,1000,0,1000,
 'CI simulated finance document','CI fixture is not real financial certification',
 '83000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000002',now());
INSERT INTO payroll_personnel_identity_link(
 id,tenant_id,personnel_employee_id,payroll_employee_id,status,
 evidence_reference,requested_by,decided_by,decided_at)
VALUES('a6000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 'a1000000-0000-4000-8000-000000000001','a2000000-0000-4000-8000-000000000001',
 'APPROVED','CI matching synthetic employee test fixture',
 '83000000-0000-4000-8000-000000000001',
 '83000000-0000-4000-8000-000000000002',now());
INSERT INTO pay_period(id,tenant_id,pay_group_id,start_date,end_date,pay_date)
VALUES('a7000000-0000-4000-8000-000000000001',
 '81000000-0000-4000-8000-000000000001',
 '91000000-0000-4000-8000-000000000001','2026-01-01','2026-01-07','2026-01-08');
INSERT INTO payroll_run(id,tenant_id,pay_group_id,pay_period_id,status,run_type)
VALUES('a8000000-0000-4000-8000-000000000001',
 '81000000-0000-4000-8000-000000000001',
 '91000000-0000-4000-8000-000000000001',
 'a7000000-0000-4000-8000-000000000001','LOCKED','REGULAR');
INSERT INTO payroll_employee(id,tenant_id,run_id,employee_id)
VALUES('a9000000-0000-4000-8000-000000000001',
 '81000000-0000-4000-8000-000000000001',
 'a8000000-0000-4000-8000-000000000001',
 'a2000000-0000-4000-8000-000000000001');
INSERT INTO payroll_line(tenant_id,run_id,payroll_employee_id,employee_id,item_code,category,amount)
VALUES('81000000-0000-4000-8000-000000000001',
 'a8000000-0000-4000-8000-000000000001',
 'a9000000-0000-4000-8000-000000000001',
 'a2000000-0000-4000-8000-000000000001','BASIC','EARNING',1000);
INSERT INTO compensation(id,tenant_id,employee_id,effective_from,pay_basis,base_rate)
VALUES('aa000000-0000-4000-8000-000000000001',
 '81000000-0000-4000-8000-000000000001',
 'a2000000-0000-4000-8000-000000000001','2026-01-01','MONTHLY',1000);
INSERT INTO dtr_day(tenant_id,employee_id,work_date,day_type,status,regular_minutes)
SELECT '81000000-0000-4000-8000-000000000001'::uuid,
 'a2000000-0000-4000-8000-000000000001'::uuid,day::date,'REGULAR','LOCKED',480
FROM generate_series('2026-01-01'::date,'2026-01-07'::date,interval '1 day') day
WHERE EXTRACT(ISODOW FROM day) BETWEEN 1 AND 5;
COMMIT;
