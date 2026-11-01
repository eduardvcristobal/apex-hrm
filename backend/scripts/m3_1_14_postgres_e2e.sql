-- M3.1.14: transactionally execute realistic final-pay and negative PostgreSQL cases.
-- This creates NO permanent employees, payments or bank transfers.
BEGIN;
CREATE ROLE m314_restricted NOLOGIN NOBYPASSRLS;
GRANT USAGE ON SCHEMA public TO m314_restricted;
GRANT SELECT ON payroll_personnel_identity_link,
 payroll_final_pay_settlement, payroll_final_pay_payment_authorization,
 payroll_final_pay_bank_evidence TO m314_restricted;
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
INSERT INTO payroll_personnel_identity_link(
 id,tenant_id,personnel_employee_id,payroll_employee_id,status,evidence_reference,
 requested_by,decided_by,decided_at)
VALUES('8a000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '84000000-0000-4000-8000-000000000001','85000000-0000-4000-8000-000000000001',
 'APPROVED','Verified HR and payroll identity in CI',
 '83000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000002',now());
INSERT INTO payroll_final_pay_payment_authorization(
 id,tenant_id,settlement_id,identity_link_id,bank_account_id,amount,status,
 source_reconciliation_reference,prepared_by,authorized_by,authorized_at)
VALUES('8b000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '89000000-0000-4000-8000-000000000001','8a000000-0000-4000-8000-000000000001',
 '86000000-0000-4000-8000-000000000001',12000,'AUTHORIZED',
 'CI-verified internal authorization',
 '83000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000002',now());
INSERT INTO payroll_final_pay_bank_evidence(
 id,tenant_id,payment_authorization_id,bank_code,bank_reference,bank_value_date,
 evidence_document_reference,reported_amount,status,recorded_by,reviewed_by,reviewed_at)
VALUES('8c000000-0000-4000-8000-000000000001','81000000-0000-4000-8000-000000000001',
 '8b000000-0000-4000-8000-000000000001','DEMO','CI-RECEIPT-001','2026-10-01',
 'Fake test receipt for CI checks',12000,'EVIDENCE_REVIEWED',
 '83000000-0000-4000-8000-000000000001','83000000-0000-4000-8000-000000000003',now());
DO $checks$
DECLARE n integer;
BEGIN
 SELECT count(*) INTO n FROM payroll_final_pay_bank_evidence WHERE bank_reference='CI-RECEIPT-001';
 IF n<>1 THEN RAISE EXCEPTION 'Expected complete settlement-to-evidence chain'; END IF;
 BEGIN
  INSERT INTO payroll_final_pay_payment_authorization(
   tenant_id,settlement_id,identity_link_id,bank_account_id,amount,source_reconciliation_reference,prepared_by)
  VALUES('81000000-0000-4000-8000-000000000001',
   '89000000-0000-4000-8000-000000000001','8a000000-0000-4000-8000-000000000001',
   '86000000-0000-4000-8000-000000000001',12000,'Repeated authorization must fail',
   '83000000-0000-4000-8000-000000000001');
  RAISE EXCEPTION 'Duplicate payment incorrectly allowed';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
  INSERT INTO payroll_final_pay_bank_evidence(
   tenant_id,payment_authorization_id,bank_code,bank_reference,bank_value_date,
   evidence_document_reference,reported_amount,recorded_by)
  VALUES('81000000-0000-4000-8000-000000000001',
   '8b000000-0000-4000-8000-000000000001','DEMO','CI-RECEIPT-001','2026-10-01',
   'Repeated bank evidence must fail',12000,'83000000-0000-4000-8000-000000000001');
  RAISE EXCEPTION 'Duplicate bank evidence incorrectly allowed';
 EXCEPTION WHEN unique_violation THEN NULL; END;
 BEGIN
  UPDATE payroll_final_pay_payment_authorization SET authorized_by=prepared_by
  WHERE id='8b000000-0000-4000-8000-000000000001';
  RAISE EXCEPTION 'Self-authorized payment incorrectly allowed';
 EXCEPTION WHEN check_violation THEN NULL; END;
 BEGIN
  UPDATE payroll_personnel_identity_link SET decided_by=requested_by
  WHERE id='8a000000-0000-4000-8000-000000000001';
  RAISE EXCEPTION 'Self-approved identity link incorrectly allowed';
 EXCEPTION WHEN check_violation THEN NULL; END;
END $checks$;
SELECT set_config('app.tenant_id','81000000-0000-4000-8000-000000000002',true);
INSERT INTO personnel_employee(id,tenant_id,company_id,employee_number,first_name,last_name,hire_date)
VALUES('84000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000002',
 '82000000-0000-4000-8000-000000000002','FP-002','Alex','Santos','2020-01-01');
INSERT INTO employee(id,tenant_id,company_id,employee_no,first_name,last_name,hire_date)
VALUES('85000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000002',
 '82000000-0000-4000-8000-000000000002','FP-002','Alex','Santos','2020-01-01');
INSERT INTO payroll_personnel_identity_link(
 id,tenant_id,personnel_employee_id,payroll_employee_id,evidence_reference,requested_by)
VALUES('8a000000-0000-4000-8000-000000000002','81000000-0000-4000-8000-000000000002',
 '84000000-0000-4000-8000-000000000002','85000000-0000-4000-8000-000000000002',
 'Second tenant fixture identity match','83000000-0000-4000-8000-000000000004');
-- Restricted role is NOT the DB owner or superuser: test actual RLS visibility.
SET LOCAL ROLE m314_restricted;
SELECT set_config('app.tenant_id','81000000-0000-4000-8000-000000000001',true);
DO $rls$
DECLARE n integer;
BEGIN
 SELECT count(*) INTO n FROM payroll_personnel_identity_link;
 IF n<>1 THEN RAISE EXCEPTION 'Tenant A visibility expected 1 identity, got %',n; END IF;
 SELECT count(*) INTO n FROM payroll_final_pay_bank_evidence;
 IF n<>1 THEN RAISE EXCEPTION 'Tenant A visibility expected 1 bank evidence, got %',n; END IF;
END $rls$;
SELECT set_config('app.tenant_id','81000000-0000-4000-8000-000000000002',true);
DO $rls$
DECLARE n integer;
BEGIN
 SELECT count(*) INTO n FROM payroll_personnel_identity_link;
 IF n<>1 THEN RAISE EXCEPTION 'Tenant B visibility expected 1 identity, got %',n; END IF;
 SELECT count(*) INTO n FROM payroll_final_pay_bank_evidence;
 IF n<>0 THEN RAISE EXCEPTION 'Tenant B saw tenant A bank evidence'; END IF;
END $rls$;
RESET ROLE;
ROLLBACK;
