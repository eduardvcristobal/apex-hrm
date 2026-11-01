-- Sequential, tenant-configurable workflow engine. Published versions are immutable snapshots.
CREATE TABLE approval_process_type (
 code varchar(50) PRIMARY KEY, module varchar(40) NOT NULL, display_name varchar(120) NOT NULL,
 enabled boolean NOT NULL DEFAULT true, created_at timestamptz NOT NULL DEFAULT now()
);
INSERT INTO approval_process_type(code,module,display_name) VALUES
 ('LEAVE','TIMEKEEPING','Leave application'),
 ('OVERTIME','TIMEKEEPING','Overtime request'),
 ('ATTENDANCE_CORRECTION','TIMEKEEPING','Attendance correction'),
 ('HEADCOUNT','RECRUITMENT','Headcount request');
CREATE TABLE approval_workflow (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id),
 name varchar(160) NOT NULL, process_code varchar(50) NOT NULL REFERENCES approval_process_type(code),
 status varchar(20) NOT NULL DEFAULT 'DRAFT', created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_approval_workflow_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_approval_workflow_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_approval_workflow_status CHECK(status IN ('DRAFT','PUBLISHED','RETIRED'))
);
CREATE TABLE approval_workflow_version (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, workflow_id uuid NOT NULL,
 version_no integer NOT NULL, status varchar(20) NOT NULL DEFAULT 'DRAFT', created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), published_by uuid, published_at timestamptz,
 CONSTRAINT uq_approval_workflow_version_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_approval_workflow_version_number UNIQUE(tenant_id,workflow_id,version_no),
 CONSTRAINT fk_approval_version_workflow FOREIGN KEY(tenant_id,workflow_id) REFERENCES approval_workflow(tenant_id,id),
 CONSTRAINT fk_approval_version_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_approval_version_publisher FOREIGN KEY(tenant_id,published_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_approval_version_status CHECK(status IN ('DRAFT','PUBLISHED','RETIRED'))
);
CREATE TABLE approval_workflow_step (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, workflow_version_id uuid NOT NULL,
 step_no integer NOT NULL, label varchar(120) NOT NULL, action_type varchar(20) NOT NULL,
 approver_type varchar(24) NOT NULL, role_code varchar(40), identity_id uuid, manager_depth integer,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_approval_step_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_approval_step_number UNIQUE(tenant_id,workflow_version_id,step_no),
 CONSTRAINT fk_approval_step_version FOREIGN KEY(tenant_id,workflow_version_id) REFERENCES approval_workflow_version(tenant_id,id),
 CONSTRAINT fk_approval_step_identity FOREIGN KEY(tenant_id,identity_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_approval_step_action CHECK(action_type IN ('REVIEW','VERIFY','APPROVE')),
 CONSTRAINT ck_approval_step_approver CHECK(approver_type IN ('REPORTING_MANAGER','MANAGER_CHAIN','ROLE','USER')),
 CONSTRAINT ck_approval_step_route CHECK((approver_type='ROLE' AND role_code IS NOT NULL AND identity_id IS NULL AND manager_depth IS NULL) OR (approver_type='USER' AND identity_id IS NOT NULL AND role_code IS NULL AND manager_depth IS NULL) OR (approver_type='REPORTING_MANAGER' AND role_code IS NULL AND identity_id IS NULL AND manager_depth IS NULL) OR (approver_type='MANAGER_CHAIN' AND manager_depth BETWEEN 1 AND 10 AND role_code IS NULL AND identity_id IS NULL))
);
CREATE TABLE approval_assignment (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), process_code varchar(50) NOT NULL REFERENCES approval_process_type(code),
 company_id uuid, department_id uuid, workflow_version_id uuid NOT NULL, active boolean NOT NULL DEFAULT true,
 created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_approval_assignment_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_approval_assignment_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_approval_assignment_department FOREIGN KEY(tenant_id,department_id) REFERENCES personnel_department(tenant_id,id),
 CONSTRAINT fk_approval_assignment_version FOREIGN KEY(tenant_id,workflow_version_id) REFERENCES approval_workflow_version(tenant_id,id),
 CONSTRAINT fk_approval_assignment_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_approval_assignment_match ON approval_assignment(tenant_id,process_code,active,company_id,department_id);
CREATE TABLE approval_instance (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), process_code varchar(50) NOT NULL REFERENCES approval_process_type(code),
 business_entity varchar(60) NOT NULL, business_id uuid NOT NULL, workflow_version_id uuid NOT NULL,
 requester_identity_id uuid NOT NULL, requester_employee_id uuid NOT NULL, company_id uuid NOT NULL, department_id uuid,
 current_step integer NOT NULL DEFAULT 1, status varchar(20) NOT NULL DEFAULT 'PENDING', summary varchar(500) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(), completed_at timestamptz,
 CONSTRAINT uq_approval_instance_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_approval_instance_business UNIQUE(tenant_id,process_code,business_id),
 CONSTRAINT fk_approval_instance_version FOREIGN KEY(tenant_id,workflow_version_id) REFERENCES approval_workflow_version(tenant_id,id),
 CONSTRAINT fk_approval_instance_requester FOREIGN KEY(tenant_id,requester_identity_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_approval_instance_employee FOREIGN KEY(tenant_id,requester_employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_approval_instance_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_approval_instance_department FOREIGN KEY(tenant_id,department_id) REFERENCES personnel_department(tenant_id,id),
 CONSTRAINT ck_approval_instance_status CHECK(status IN ('PENDING','APPROVED','REJECTED','CANCELLED','ROUTING_ERROR'))
);
CREATE INDEX ix_approval_instance_requester ON approval_instance(tenant_id,requester_identity_id,created_at DESC);
CREATE TABLE approval_task (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, instance_id uuid NOT NULL,
 step_no integer NOT NULL, assigned_to uuid NOT NULL, status varchar(20) NOT NULL DEFAULT 'PENDING',
 assigned_at timestamptz NOT NULL DEFAULT now(), decided_at timestamptz,
 CONSTRAINT uq_approval_task_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_approval_task_assignment UNIQUE(tenant_id,instance_id,step_no,assigned_to),
 CONSTRAINT fk_approval_task_instance FOREIGN KEY(tenant_id,instance_id) REFERENCES approval_instance(tenant_id,id),
 CONSTRAINT fk_approval_task_assignee FOREIGN KEY(tenant_id,assigned_to) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_approval_task_status CHECK(status IN ('PENDING','APPROVED','REJECTED','SUPERSEDED','CANCELLED'))
);
CREATE INDEX ix_approval_task_inbox ON approval_task(tenant_id,assigned_to,status,assigned_at DESC);
CREATE TABLE approval_decision (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, task_id uuid NOT NULL,
 decided_by uuid NOT NULL, decision varchar(20) NOT NULL, comments text NOT NULL DEFAULT '', created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_approval_decision_task FOREIGN KEY(tenant_id,task_id) REFERENCES approval_task(tenant_id,id),
 CONSTRAINT fk_approval_decision_actor FOREIGN KEY(tenant_id,decided_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_approval_decision_value CHECK(decision IN ('APPROVE','REJECT'))
);
CREATE TABLE approval_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL, instance_id uuid NOT NULL,
 actor_id uuid, event_type varchar(40) NOT NULL, step_no integer, details jsonb NOT NULL DEFAULT '{}'::jsonb,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_approval_event_instance FOREIGN KEY(tenant_id,instance_id) REFERENCES approval_instance(tenant_id,id),
 CONSTRAINT fk_approval_event_actor FOREIGN KEY(tenant_id,actor_id) REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_approval_event_instance ON approval_event(tenant_id,instance_id,created_at DESC);
DO $$ DECLARE tab text; BEGIN FOREACH tab IN ARRAY ARRAY['approval_workflow','approval_workflow_version','approval_workflow_step','approval_assignment','approval_instance','approval_task','approval_decision','approval_event'] LOOP EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab); EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab); EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id())',tab||'_tenant',tab); END LOOP; END $$;
