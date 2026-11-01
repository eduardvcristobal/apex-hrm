-- The newer personnel directory and legacy payroll/timekeeping employee tables have independent IDs.
-- Keep an explicit tenant-scoped link so self-service uses the correct owning records.
ALTER TABLE employee ADD CONSTRAINT uq_employee_tenant_id UNIQUE(tenant_id,id);
CREATE TABLE personnel_timekeeping_employee (
 tenant_id uuid NOT NULL REFERENCES tenant(id), personnel_employee_id uuid NOT NULL, employee_id uuid NOT NULL,
 linked_at timestamptz NOT NULL DEFAULT now(),
 PRIMARY KEY(tenant_id,personnel_employee_id),
 CONSTRAINT uq_personnel_timekeeping_employee UNIQUE(tenant_id,employee_id),
 CONSTRAINT fk_pt_employee_personnel FOREIGN KEY(tenant_id,personnel_employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_pt_employee_legacy FOREIGN KEY(tenant_id,employee_id) REFERENCES employee(tenant_id,id)
);
INSERT INTO personnel_timekeeping_employee(tenant_id,personnel_employee_id,employee_id)
SELECT p.tenant_id,p.id,e.id FROM personnel_employee p JOIN employee e
 ON e.tenant_id=p.tenant_id AND e.company_id=p.company_id AND e.employee_no=p.employee_number;
INSERT INTO employee(id,tenant_id,company_id,employee_no,first_name,last_name,hire_date,status)
SELECT p.id,p.tenant_id,p.company_id,
 CASE WHEN EXISTS(SELECT 1 FROM employee e WHERE e.tenant_id=p.tenant_id AND e.employee_no=p.employee_number)
      THEN c.company_code||'-'||p.employee_number ELSE p.employee_number END,
 p.first_name,p.last_name,p.hire_date,
 CASE WHEN p.employment_status IN ('TERMINATED','RETIRED') THEN 'SEPARATED' ELSE 'ACTIVE' END
FROM personnel_employee p JOIN company c ON c.tenant_id=p.tenant_id AND c.id=p.company_id
WHERE NOT EXISTS(SELECT 1 FROM personnel_timekeeping_employee l WHERE l.tenant_id=p.tenant_id AND l.personnel_employee_id=p.id)
ON CONFLICT(id) DO NOTHING;
INSERT INTO personnel_timekeeping_employee(tenant_id,personnel_employee_id,employee_id)
SELECT p.tenant_id,p.id,e.id FROM personnel_employee p JOIN company c ON c.tenant_id=p.tenant_id AND c.id=p.company_id
JOIN employee e ON e.tenant_id=p.tenant_id AND e.company_id=p.company_id
 AND e.employee_no IN (p.employee_number,c.company_code||'-'||p.employee_number)
WHERE NOT EXISTS(SELECT 1 FROM personnel_timekeeping_employee l WHERE l.tenant_id=p.tenant_id AND l.personnel_employee_id=p.id)
ON CONFLICT DO NOTHING;
ALTER TABLE personnel_timekeeping_employee ENABLE ROW LEVEL SECURITY;
ALTER TABLE personnel_timekeeping_employee FORCE ROW LEVEL SECURITY;
CREATE POLICY personnel_timekeeping_employee_tenant ON personnel_timekeeping_employee USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
CREATE UNIQUE INDEX uq_approval_assignment_scope ON approval_assignment(tenant_id,process_code,coalesce(company_id,'00000000-0000-0000-0000-000000000000'::uuid),coalesce(department_id,'00000000-0000-0000-0000-000000000000'::uuid));
