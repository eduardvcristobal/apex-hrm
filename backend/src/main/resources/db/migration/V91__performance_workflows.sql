-- M5.5-M5.8: explicit employee identity linkage and auditable performance workflows.
-- Auth identity links are provisioned by HR, never inferred from names/emails.
CREATE TABLE performance_employee_identity (
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 identity_id uuid NOT NULL,
 linked_at timestamptz NOT NULL DEFAULT now(),
 linked_by uuid NOT NULL,
 PRIMARY KEY(tenant_id,employee_id),
 CONSTRAINT uq_performance_identity UNIQUE(tenant_id,identity_id),
 CONSTRAINT fk_performance_link_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_performance_link_identity FOREIGN KEY(tenant_id,identity_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_performance_link_author FOREIGN KEY(tenant_id,linked_by) REFERENCES auth_identity(tenant_id,id)
);
CREATE TABLE performance_goal_progress (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 goal_id uuid NOT NULL,
 actual_value numeric(14,3) NOT NULL CHECK(actual_value>=0),
 note varchar(2000),
 recorded_by uuid NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_performance_progress_goal FOREIGN KEY(tenant_id,goal_id) REFERENCES performance_goal(tenant_id,id),
 CONSTRAINT fk_performance_progress_author FOREIGN KEY(tenant_id,recorded_by) REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_performance_goal_progress ON performance_goal_progress(tenant_id,goal_id,recorded_at DESC);
ALTER TABLE performance_review ADD COLUMN self_assessment text;
ALTER TABLE performance_review ADD COLUMN self_rating numeric(3,2) CHECK(self_rating BETWEEN 1 AND 5);
ALTER TABLE performance_review ADD COLUMN employee_acknowledged_at timestamptz;
ALTER TABLE performance_review ADD COLUMN finalized_at timestamptz;
ALTER TABLE performance_review ADD COLUMN finalized_by uuid;
ALTER TABLE performance_review ADD CONSTRAINT fk_performance_review_finalizer
 FOREIGN KEY (tenant_id,finalized_by) REFERENCES auth_identity(tenant_id,id);
ALTER TABLE performance_review DROP CONSTRAINT ck_performance_review_status;
ALTER TABLE performance_review ADD CONSTRAINT ck_performance_review_status
 CHECK (status IN ('DRAFT','SUBMITTED','ACKNOWLEDGED','FINALIZED'));
ALTER TABLE performance_development_plan ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();
ALTER TABLE performance_employee_identity ENABLE ROW LEVEL SECURITY;
ALTER TABLE performance_employee_identity FORCE ROW LEVEL SECURITY;
CREATE POLICY performance_link_tenant ON performance_employee_identity
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE performance_goal_progress ENABLE ROW LEVEL SECURITY;
ALTER TABLE performance_goal_progress FORCE ROW LEVEL SECURITY;
CREATE POLICY performance_progress_tenant ON performance_goal_progress
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
