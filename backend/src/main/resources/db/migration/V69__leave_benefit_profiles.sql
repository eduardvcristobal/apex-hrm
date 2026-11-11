ALTER TABLE leave_type ADD CONSTRAINT uq_leave_type_tenant_id_id UNIQUE (tenant_id, id);
ALTER TABLE employee ADD CONSTRAINT uq_employee_tenant_id_id UNIQUE (tenant_id, id);

CREATE TABLE leave_benefit_profile (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    code varchar(40) NOT NULL,
    name varchar(120) NOT NULL,
    employee_class varchar(40) NOT NULL DEFAULT 'GENERAL',
    description varchar(1000),
    active boolean NOT NULL DEFAULT true,
    UNIQUE (tenant_id, code),
    UNIQUE (tenant_id, id)
);
CREATE INDEX idx_leave_benefit_profile_tenant ON leave_benefit_profile(tenant_id);

CREATE TABLE leave_benefit_entitlement (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    profile_id uuid NOT NULL,
    leave_type_id uuid NOT NULL,
    annual_entitlement numeric(18,4) NOT NULL CHECK (annual_entitlement >= 0),
    paid boolean NOT NULL DEFAULT true,
    hire_proration_enabled boolean NOT NULL DEFAULT true,
    convertible boolean NOT NULL DEFAULT false,
    carry_over_max numeric(18,4),
    effective_from date NOT NULL,
    effective_to date,
    UNIQUE (tenant_id, profile_id, leave_type_id, effective_from),
    FOREIGN KEY (tenant_id, profile_id) REFERENCES leave_benefit_profile(tenant_id, id),
    FOREIGN KEY (tenant_id, leave_type_id) REFERENCES leave_type(tenant_id, id),
    CHECK (effective_to IS NULL OR effective_to >= effective_from),
    CHECK (carry_over_max IS NULL OR carry_over_max >= 0)
);
CREATE INDEX idx_leave_benefit_entitlement_profile ON leave_benefit_entitlement(tenant_id, profile_id, effective_from);

CREATE TABLE employee_leave_benefit_assignment (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    version bigint NOT NULL DEFAULT 0,
    employee_id uuid NOT NULL,
    profile_id uuid NOT NULL,
    effective_from date NOT NULL,
    effective_to date,
    assigned_by uuid,
    reason varchar(1000),
    FOREIGN KEY (tenant_id, employee_id) REFERENCES employee(tenant_id, id),
    FOREIGN KEY (tenant_id, profile_id) REFERENCES leave_benefit_profile(tenant_id, id),
    CHECK (effective_to IS NULL OR effective_to >= effective_from)
);
CREATE INDEX idx_employee_leave_benefit_assignment_employee ON employee_leave_benefit_assignment(tenant_id, employee_id, effective_from);

CREATE UNIQUE INDEX uq_leave_profile_assignment_credit
    ON leave_ledger(tenant_id, reference_id, leave_type_id)
    WHERE reference_type = 'BENEFIT_PROFILE_ASSIGNMENT';


CREATE UNIQUE INDEX uq_leave_profile_expiry
    ON leave_ledger(tenant_id, reference_id, leave_type_id, entry_date)
    WHERE reference_type = 'BENEFIT_PROFILE_EXPIRY';

CREATE UNIQUE INDEX uq_leave_profile_annual_credit
    ON leave_ledger(tenant_id, reference_id, leave_type_id, entry_date)
    WHERE reference_type = 'BENEFIT_PROFILE_ANNUAL';
