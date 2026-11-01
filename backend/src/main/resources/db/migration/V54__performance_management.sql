-- M5.1 Performance foundation; reserved V90 to avoid ongoing M3 migrations.
CREATE TABLE performance_cycle (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 name varchar(160) NOT NULL,
 starts_on date NOT NULL,
 ends_on date NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'DRAFT',
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_performance_cycle_tenant UNIQUE(tenant_id,id),
 CONSTRAINT fk_performance_cycle_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_performance_cycle_author FOREIGN KEY (tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_performance_cycle_dates CHECK(ends_on >= starts_on),
 CONSTRAINT ck_performance_cycle_status CHECK(status IN ('DRAFT','ACTIVE','CLOSED'))
);
CREATE TABLE performance_goal (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 cycle_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 title varchar(200) NOT NULL,
 description text,
 metric varchar(160),
 target_value numeric(14,3),
 actual_value numeric(14,3),
 weight numeric(5,2) NOT NULL DEFAULT 1,
 status varchar(20) NOT NULL DEFAULT 'DRAFT',
 due_on date,
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_performance_goal_tenant UNIQUE(tenant_id,id),
 CONSTRAINT fk_performance_goal_cycle FOREIGN KEY(tenant_id,cycle_id) REFERENCES performance_cycle(tenant_id,id),
 CONSTRAINT fk_performance_goal_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_performance_goal_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_performance_goal_weight CHECK(weight > 0 AND weight <= 100),
 CONSTRAINT ck_performance_goal_status CHECK(status IN ('DRAFT','ACTIVE','COMPLETED','CANCELLED')),
 CONSTRAINT ck_performance_goal_target CHECK(target_value IS NULL OR target_value >= 0)
);
CREATE TABLE performance_review (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 cycle_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 reviewer_id uuid NOT NULL,
 rating numeric(3,2),
 strengths text,
 improvements text,
 manager_comments text,
 status varchar(20) NOT NULL DEFAULT 'DRAFT',
 submitted_at timestamptz,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_performance_review_tenant UNIQUE(tenant_id,id),
 CONSTRAINT uq_performance_review_assignment UNIQUE(tenant_id,cycle_id,employee_id,reviewer_id),
 CONSTRAINT fk_performance_review_cycle FOREIGN KEY(tenant_id,cycle_id) REFERENCES performance_cycle(tenant_id,id),
 CONSTRAINT fk_performance_review_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_performance_review_reviewer FOREIGN KEY(tenant_id,reviewer_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_performance_review_rating CHECK(rating IS NULL OR (rating >= 1 AND rating <= 5)),
 CONSTRAINT ck_performance_review_status CHECK(status IN ('DRAFT','SUBMITTED','ACKNOWLEDGED'))
);
CREATE TABLE performance_development_plan (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 review_id uuid NOT NULL,
 title varchar(200) NOT NULL,
 action_items text NOT NULL,
 target_date date,
 status varchar(20) NOT NULL DEFAULT 'PLANNED',
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_performance_plan_review FOREIGN KEY (tenant_id,review_id) REFERENCES performance_review(tenant_id,id),
 CONSTRAINT ck_performance_plan_status CHECK(status IN ('PLANNED','IN_PROGRESS','COMPLETED','CANCELLED'))
);
CREATE INDEX ix_performance_cycle_company ON performance_cycle(tenant_id,company_id,starts_on);
CREATE INDEX ix_performance_goal_employee ON performance_goal(tenant_id,employee_id,cycle_id);
CREATE INDEX ix_performance_review_employee ON performance_review(tenant_id,employee_id,cycle_id);
CREATE INDEX ix_performance_plan_review ON performance_development_plan(tenant_id,review_id);
DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['performance_cycle','performance_goal','performance_review','performance_development_plan']
 LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id())',t || '_tenant',t);
 END LOOP;
END $$;
