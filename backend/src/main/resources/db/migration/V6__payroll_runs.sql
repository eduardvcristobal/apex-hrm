-- Generated from the HRM schema spec. Tenant-scoped tables carry tenant_id; G tables are global reference data.

CREATE TABLE payroll_run (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  pay_period_id uuid NOT NULL REFERENCES pay_period(id),
  pay_group_id uuid NOT NULL REFERENCES pay_group(id),
  run_type varchar(255) NOT NULL DEFAULT 'REGULAR',
  status varchar(255) NOT NULL DEFAULT 'DRAFT',
  computed_at timestamptz,
  approved_by uuid,
  approved_at timestamptz,
  locked_at timestamptz,
  paid_at timestamptz,
  remarks text,
  employee_count integer NOT NULL DEFAULT 0,
  total_gross numeric(18,4) NOT NULL DEFAULT 0,
  total_deductions numeric(18,4) NOT NULL DEFAULT 0,
  total_net numeric(18,4) NOT NULL DEFAULT 0,
  total_employer_cost numeric(18,4) NOT NULL DEFAULT 0
);
CREATE INDEX idx_payroll_run_tenant ON payroll_run(tenant_id);
CREATE INDEX idx_payroll_run_pay_period_id ON payroll_run(pay_period_id);
CREATE INDEX idx_payroll_run_pay_group_id ON payroll_run(pay_group_id);

CREATE TABLE payroll_employee (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  run_id uuid NOT NULL REFERENCES payroll_run(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  cost_center_id uuid REFERENCES cost_center(id),
  gross numeric(18,4) NOT NULL DEFAULT 0,
  taxable_income numeric(18,4) NOT NULL DEFAULT 0,
  total_deductions numeric(18,4) NOT NULL DEFAULT 0,
  net_pay numeric(18,4) NOT NULL DEFAULT 0,
  employer_cost numeric(18,4) NOT NULL DEFAULT 0,
  warnings text,
  CONSTRAINT uq_payroll_employee_tenant_id_run_id_employee_id UNIQUE (tenant_id, run_id, employee_id)
);
CREATE INDEX idx_payroll_employee_tenant ON payroll_employee(tenant_id);
CREATE INDEX idx_payroll_employee_run_id ON payroll_employee(run_id);
CREATE INDEX idx_payroll_employee_employee_id ON payroll_employee(employee_id);
CREATE INDEX idx_payroll_employee_cost_center_id ON payroll_employee(cost_center_id);

CREATE TABLE payroll_line (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  run_id uuid NOT NULL REFERENCES payroll_run(id),
  payroll_employee_id uuid NOT NULL REFERENCES payroll_employee(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  item_code varchar(255) NOT NULL,
  description varchar(255),
  category varchar(255) NOT NULL,
  quantity numeric(18,4),
  rate numeric(18,4),
  amount numeric(18,4) NOT NULL,
  taxable boolean NOT NULL DEFAULT true,
  source_type varchar(255),
  source_id uuid,
  cost_center_id uuid REFERENCES cost_center(id)
);
CREATE INDEX idx_payroll_line_tenant ON payroll_line(tenant_id);
CREATE INDEX idx_payroll_line_run_id ON payroll_line(run_id);
CREATE INDEX idx_payroll_line_payroll_employee_id ON payroll_line(payroll_employee_id);
CREATE INDEX idx_payroll_line_employee_id ON payroll_line(employee_id);
CREATE INDEX idx_payroll_line_cost_center_id ON payroll_line(cost_center_id);

CREATE TABLE adjustment (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  item_code varchar(255) NOT NULL,
  description varchar(255),
  category varchar(255) NOT NULL,
  amount numeric(18,4) NOT NULL,
  taxable boolean NOT NULL DEFAULT true,
  reason text NOT NULL,
  source_period_id uuid REFERENCES pay_period(id),
  target_period_id uuid REFERENCES pay_period(id),
  status varchar(255) NOT NULL DEFAULT 'PENDING',
  applied_run_id uuid REFERENCES payroll_run(id),
  requested_by uuid,
  approved_by uuid,
  approved_at timestamptz
);
CREATE INDEX idx_adjustment_tenant ON adjustment(tenant_id);
CREATE INDEX idx_adjustment_employee_id ON adjustment(employee_id);
CREATE INDEX idx_adjustment_source_period_id ON adjustment(source_period_id);
CREATE INDEX idx_adjustment_target_period_id ON adjustment(target_period_id);
CREATE INDEX idx_adjustment_applied_run_id ON adjustment(applied_run_id);

CREATE TABLE payslip (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  payroll_employee_id uuid NOT NULL REFERENCES payroll_employee(id),
  employee_id uuid NOT NULL REFERENCES employee(id),
  run_id uuid NOT NULL REFERENCES payroll_run(id),
  version_no integer NOT NULL DEFAULT 1,
  snapshot text NOT NULL,
  issued_at timestamptz NOT NULL
);
CREATE INDEX idx_payslip_tenant ON payslip(tenant_id);
CREATE INDEX idx_payslip_payroll_employee_id ON payslip(payroll_employee_id);
CREATE INDEX idx_payslip_employee_id ON payslip(employee_id);
CREATE INDEX idx_payslip_run_id ON payslip(run_id);

CREATE TABLE journal_entry (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  run_id uuid NOT NULL REFERENCES payroll_run(id),
  entry_date date NOT NULL,
  description varchar(255),
  status varchar(255) NOT NULL DEFAULT 'DRAFT',
  total_debit numeric(18,4) NOT NULL DEFAULT 0,
  total_credit numeric(18,4) NOT NULL DEFAULT 0
);
CREATE INDEX idx_journal_entry_tenant ON journal_entry(tenant_id);
CREATE INDEX idx_journal_entry_run_id ON journal_entry(run_id);

CREATE TABLE journal_entry_line (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  journal_entry_id uuid NOT NULL REFERENCES journal_entry(id),
  account_id uuid NOT NULL REFERENCES gl_account(id),
  cost_center_id uuid REFERENCES cost_center(id),
  debit numeric(18,4) NOT NULL DEFAULT 0,
  credit numeric(18,4) NOT NULL DEFAULT 0,
  memo varchar(255)
);
CREATE INDEX idx_journal_entry_line_tenant ON journal_entry_line(tenant_id);
CREATE INDEX idx_journal_entry_line_journal_entry_id ON journal_entry_line(journal_entry_id);
CREATE INDEX idx_journal_entry_line_account_id ON journal_entry_line(account_id);
CREATE INDEX idx_journal_entry_line_cost_center_id ON journal_entry_line(cost_center_id);

CREATE TABLE bank_file (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  run_id uuid NOT NULL REFERENCES payroll_run(id),
  bank_template_id uuid NOT NULL REFERENCES bank_template(id),
  file_name varchar(255) NOT NULL,
  content text NOT NULL,
  record_count integer NOT NULL DEFAULT 0,
  total_amount numeric(18,4) NOT NULL DEFAULT 0
);
CREATE INDEX idx_bank_file_tenant ON bank_file(tenant_id);
CREATE INDEX idx_bank_file_run_id ON bank_file(run_id);
CREATE INDEX idx_bank_file_bank_template_id ON bank_file(bank_template_id);
