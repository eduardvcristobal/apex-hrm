-- Ephemeral GitHub Actions PostgreSQL ONLY. Do not run against production.
-- Use independently verified static CI-only bearer hashes. No bank endpoints exist.
BEGIN;
INSERT INTO tenant(id,code,name,slug,display_name) VALUES
 ('81000000-0000-4000-8000-000000000001','CI-M314-A','CI Payroll A','ci-m314-a','CI Payroll A'),
 ('81000000-0000-4000-8000-000000000002','CI-M314-B','CI Payroll B','ci-m314-b','CI Payroll B');
INSERT INTO company(id,tenant_id,legal_name,company_code) VALUES
 ('82000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','CI Company A','CI-A'),
 ('82000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000002','CI Company B','CI-B');
INSERT INTO auth_identity(id,tenant_id,email,password_hash) VALUES
 ('83000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001','ci314-a1@example.invalid','unused'),
 ('83000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000001','ci314-a2@example.invalid','unused'),
 ('83000000-0000-4000-8000-000000000003','81000000-0000-4000-8000-000000000001','ci314-a3@example.invalid','unused'),
 ('83000000-0000-4000-8000-000000000004','81000000-0000-4000-8000-000000000002','ci314-b1@example.invalid','unused');
SELECT set_config('app.tenant_id','81000000-0000-4000-8000-000000000001',true);
INSERT INTO personnel_employee(id,tenant_id,company_id,employee_number,first_name,last_name,hire_date,employment_status)
VALUES('84000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '82000000-0000-4000-8000-000000000001','FP-001','Jamie','Rivera','2020-01-01','TERMINATED');
INSERT INTO employee(id,tenant_id,company_id,employee_no,first_name,last_name,hire_date,status)
VALUES('85000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '82000000-0000-4000-8000-000000000001','FP-001','Jamie','Rivera','2020-01-01','ACTIVE');
INSERT INTO employee_bank_account(id,tenant_id,employee_id,bank_code,account_no,primary_account)
VALUES('86000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '85000000-0000-4000-8000-000000000001','DEMO','00000001234',true);
INSERT INTO personnel_hr_case(id,tenant_id,employee_id,case_type,status,effective_date,reason,requested_by,decided_by,decided_at)
VALUES('87000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '84000000-0000-4000-8000-000000000001','SEPARATION','APPROVED','2026-10-01','CI separation',
 '83000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000002',now());
INSERT INTO personnel_final_pay_handoff(id,tenant_id,employee_id,hr_case_id,created_by)
VALUES('88000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '84000000-0000-4000-8000-000000000001','87000000-0000-4000-8000-000000000001',
 '83000000-0000-4000-8000-000000000001');
INSERT INTO payroll_final_pay_settlement(
 id,tenant_id,handoff_id,employee_id,case_id,status,
 unpaid_basic,accrued_thirteenth_month,unused_leave_conversion,separation_pay,
 other_earnings,outstanding_loans,accountable_property,withholding_tax,
 other_authorized_deductions,gross,deductions,net,accounting_reference,justification,
 requested_by,approved_by,approved_at)
VALUES('89000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '88000000-0000-4000-8000-000000000001','84000000-0000-4000-8000-000000000001',
 '87000000-0000-4000-8000-000000000001','APPROVED',
 10000,2000,1000,0,0,500,0,500,0,13000,1000,12000,
 'CI documented finance review','Verified final-pay source components during CI',
 '83000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000002',now());

INSERT INTO auth_identity(id,tenant_id,email,password_hash) VALUES
 ('83000000-0000-4000-8000-000000000005','81000000-0000-4000-8000-000000000001',
  'ci315-nomfa@example.invalid','unused');
INSERT INTO auth_identity_role(identity_id,role) VALUES
 ('83000000-0000-4000-8000-000000000001','PAYROLL_OFFICER'),
 ('83000000-0000-4000-8000-000000000001','TENANT_ADMIN'),
 ('83000000-0000-4000-8000-000000000002','PAYROLL_OFFICER'),
 ('83000000-0000-4000-8000-000000000003','PAYROLL_OFFICER'),
 ('83000000-0000-4000-8000-000000000004','PAYROLL_OFFICER'),
 ('83000000-0000-4000-8000-000000000005','PAYROLL_OFFICER');
INSERT INTO auth_api_token(identity_id,token_sha256,expires_at,mfa_verified) VALUES
 ('83000000-0000-4000-8000-000000000001',
    encode(sha256(convert_to(repeat('a',64),'UTF8')),'hex'),now()+interval '2 hours',true),
 ('83000000-0000-4000-8000-000000000002',
    encode(sha256(convert_to(repeat('b',64),'UTF8')),'hex'),now()+interval '2 hours',true),
 ('83000000-0000-4000-8000-000000000003',
    encode(sha256(convert_to(repeat('c',64),'UTF8')),'hex'),now()+interval '2 hours',true),
 ('83000000-0000-4000-8000-000000000004',
    encode(sha256(convert_to(repeat('d',64),'UTF8')),'hex'),now()+interval '2 hours',true),
 ('83000000-0000-4000-8000-000000000005',
    encode(sha256(convert_to(repeat('e',64),'UTF8')),'hex'),now()+interval '2 hours',false);
-- Mixed realistic ledger data. The pending final-pay loan claim is 500, but source has 700.
INSERT INTO loan(id,tenant_id,employee_id,loan_type,principal,amortization,balance,start_date,status)
VALUES('8d000000-0000-4000-8000-000000000001',
 '81000000-0000-4000-8000-000000000001',
 '85000000-0000-4000-8000-000000000001',
 'CI_TEST',1500,150,700,'2025-01-01','ACTIVE');
INSERT INTO leave_type(id,tenant_id,code,name,paid,convertible)
VALUES('8e000000-0000-4000-8000-000000000001',
 '81000000-0000-4000-8000-000000000001','CI_LEAVE','CI Convertible Leave',true,true);
INSERT INTO leave_ledger(id,tenant_id,employee_id,leave_type_id,entry_date,entry_type,days)
VALUES('8f000000-0000-4000-8000-000000000001',
 '81000000-0000-4000-8000-000000000001',
 '85000000-0000-4000-8000-000000000001',
 '8e000000-0000-4000-8000-000000000001',
 '2026-09-01','GRANT',4.5);
COMMIT;
