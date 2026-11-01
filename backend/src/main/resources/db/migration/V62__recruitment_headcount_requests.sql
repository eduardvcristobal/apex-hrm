CREATE TABLE recruitment_headcount_request (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), company_id uuid NOT NULL, department_id uuid NOT NULL,
 requested_by_employee_id uuid NOT NULL, role_title varchar(180) NOT NULL, employment_type varchar(40) NOT NULL DEFAULT 'FULL_TIME',
 positions integer NOT NULL DEFAULT 1, target_date date, business_justification text NOT NULL,
 status varchar(24) NOT NULL DEFAULT 'PENDING', approved_job_id uuid, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(), approved_at timestamptz,
 CONSTRAINT uq_recruitment_headcount_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_headcount_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_headcount_department FOREIGN KEY(tenant_id,department_id) REFERENCES personnel_department(tenant_id,id),
 CONSTRAINT fk_headcount_requester FOREIGN KEY(tenant_id,requested_by_employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_headcount_job FOREIGN KEY(tenant_id,approved_job_id) REFERENCES recruitment_job(tenant_id,id),
 CONSTRAINT ck_headcount_positions CHECK(positions BETWEEN 1 AND 100),
 CONSTRAINT ck_headcount_status CHECK(status IN ('PENDING','APPROVED','REJECTED'))
);
CREATE INDEX ix_headcount_requester ON recruitment_headcount_request(tenant_id,requested_by_employee_id,created_at DESC);
ALTER TABLE recruitment_headcount_request ENABLE ROW LEVEL SECURITY;
ALTER TABLE recruitment_headcount_request FORCE ROW LEVEL SECURITY;
CREATE POLICY recruitment_headcount_tenant ON recruitment_headcount_request USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
