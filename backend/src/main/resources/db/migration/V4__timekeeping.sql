-- Generated from the HRM schema spec. Tenant-scoped tables carry tenant_id; G tables are global reference data.

CREATE TABLE holiday (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  holiday_date date NOT NULL,
  name varchar(255) NOT NULL,
  holiday_type varchar(255) NOT NULL,
  work_location_id uuid REFERENCES work_location(id),
  CONSTRAINT uq_holiday_tenant_id_holiday_date_name UNIQUE (tenant_id, holiday_date, name)
);
CREATE INDEX idx_holiday_tenant ON holiday(tenant_id);
CREATE INDEX idx_holiday_work_location_id ON holiday(work_location_id);

CREATE TABLE work_schedule (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  start_time time NOT NULL,
  end_time time NOT NULL,
  break_minutes integer NOT NULL DEFAULT 60,
  grace_minutes integer NOT NULL DEFAULT 0,
  rest_days varchar(255) NOT NULL DEFAULT 'SAT,SUN',
  CONSTRAINT uq_work_schedule_tenant_id_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_work_schedule_tenant ON work_schedule(tenant_id);

CREATE TABLE employee_schedule (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  schedule_id uuid NOT NULL REFERENCES work_schedule(id),
  effective_from date NOT NULL,
  effective_to date
);
CREATE INDEX idx_employee_schedule_tenant ON employee_schedule(tenant_id);
CREATE INDEX idx_employee_schedule_employee_id ON employee_schedule(employee_id);
CREATE INDEX idx_employee_schedule_schedule_id ON employee_schedule(schedule_id);

CREATE TABLE time_log (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  log_time timestamptz NOT NULL,
  log_type varchar(255) NOT NULL,
  source varchar(255) NOT NULL,
  latitude numeric(18,4),
  longitude numeric(18,4),
  device_id varchar(255),
  photo_ref varchar(255),
  status varchar(255) NOT NULL DEFAULT 'VALID',
  remarks varchar(255)
);
CREATE INDEX idx_time_log_tenant ON time_log(tenant_id);
CREATE INDEX idx_time_log_employee_id ON time_log(employee_id);

CREATE TABLE attendance_correction (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  work_date date NOT NULL,
  requested_in timestamptz,
  requested_out timestamptz,
  reason text,
  status varchar(255) NOT NULL DEFAULT 'PENDING',
  approved_by uuid,
  approved_at timestamptz
);
CREATE INDEX idx_attendance_correction_tenant ON attendance_correction(tenant_id);
CREATE INDEX idx_attendance_correction_employee_id ON attendance_correction(employee_id);

CREATE TABLE dtr_day (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  work_date date NOT NULL,
  schedule_id uuid REFERENCES work_schedule(id),
  day_type varchar(255) NOT NULL,
  first_in timestamptz,
  last_out timestamptz,
  regular_minutes integer NOT NULL DEFAULT 0,
  late_minutes integer NOT NULL DEFAULT 0,
  undertime_minutes integer NOT NULL DEFAULT 0,
  night_minutes integer NOT NULL DEFAULT 0,
  night_ot_minutes integer NOT NULL DEFAULT 0,
  ot_detected_minutes integer NOT NULL DEFAULT 0,
  ot_approved_minutes integer NOT NULL DEFAULT 0,
  absent boolean NOT NULL DEFAULT false,
  paid_leave boolean NOT NULL DEFAULT false,
  status varchar(255) NOT NULL DEFAULT 'OPEN',
  CONSTRAINT uq_dtr_day_tenant_id_employee_id_work_date UNIQUE (tenant_id, employee_id, work_date)
);
CREATE INDEX idx_dtr_day_tenant ON dtr_day(tenant_id);
CREATE INDEX idx_dtr_day_employee_id ON dtr_day(employee_id);
CREATE INDEX idx_dtr_day_schedule_id ON dtr_day(schedule_id);

CREATE TABLE leave_type (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  code varchar(255) NOT NULL,
  name varchar(255) NOT NULL,
  paid boolean NOT NULL DEFAULT true,
  annual_entitlement numeric(18,4),
  convertible boolean NOT NULL DEFAULT false,
  carry_over_max numeric(18,4),
  CONSTRAINT uq_leave_type_tenant_id_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_leave_type_tenant ON leave_type(tenant_id);

CREATE TABLE leave_ledger (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  leave_type_id uuid NOT NULL REFERENCES leave_type(id),
  entry_date date NOT NULL,
  entry_type varchar(255) NOT NULL,
  days numeric(18,4) NOT NULL,
  reference_type varchar(255),
  reference_id uuid,
  remarks varchar(255)
);
CREATE INDEX idx_leave_ledger_tenant ON leave_ledger(tenant_id);
CREATE INDEX idx_leave_ledger_employee_id ON leave_ledger(employee_id);
CREATE INDEX idx_leave_ledger_leave_type_id ON leave_ledger(leave_type_id);

CREATE TABLE leave_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  leave_type_id uuid NOT NULL REFERENCES leave_type(id),
  date_from date NOT NULL,
  date_to date NOT NULL,
  days numeric(18,4) NOT NULL,
  half_day boolean NOT NULL DEFAULT false,
  reason text,
  status varchar(255) NOT NULL DEFAULT 'PENDING',
  approved_by uuid,
  approved_at timestamptz
);
CREATE INDEX idx_leave_request_tenant ON leave_request(tenant_id);
CREATE INDEX idx_leave_request_employee_id ON leave_request(employee_id);
CREATE INDEX idx_leave_request_leave_type_id ON leave_request(leave_type_id);

CREATE TABLE overtime_request (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  employee_id uuid NOT NULL REFERENCES employee(id),
  work_date date NOT NULL,
  minutes_requested integer NOT NULL,
  reason text,
  status varchar(255) NOT NULL DEFAULT 'PENDING',
  approved_minutes integer,
  approved_by uuid,
  approved_at timestamptz
);
CREATE INDEX idx_overtime_request_tenant ON overtime_request(tenant_id);
CREATE INDEX idx_overtime_request_employee_id ON overtime_request(employee_id);
