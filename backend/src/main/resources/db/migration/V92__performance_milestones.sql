-- M5.14: action-level development-plan milestones and evidence.
CREATE TABLE performance_plan_milestone (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 plan_id uuid NOT NULL,
 title varchar(200) NOT NULL,
 description varchar(4000),
 due_on date,
 status varchar(20) NOT NULL DEFAULT 'PLANNED',
 completed_at timestamptz,
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_performance_milestone_tenant UNIQUE(tenant_id,id),
 CONSTRAINT fk_performance_milestone_plan FOREIGN KEY(tenant_id,plan_id)
   REFERENCES performance_development_plan(tenant_id,id),
 CONSTRAINT fk_performance_milestone_author FOREIGN KEY(tenant_id,created_by)
   REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_performance_milestone_status CHECK(status IN ('PLANNED','IN_PROGRESS','COMPLETED','CANCELLED')),
 CONSTRAINT ck_performance_milestone_completed CHECK(status <> 'COMPLETED' OR completed_at IS NOT NULL)
);
CREATE INDEX ix_performance_milestone_plan ON performance_plan_milestone(tenant_id,plan_id,status);
CREATE TABLE performance_milestone_evidence (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 milestone_id uuid NOT NULL,
 note varchar(4000) NOT NULL,
 recorded_by uuid NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_performance_evidence_milestone FOREIGN KEY(tenant_id,milestone_id)
    REFERENCES performance_plan_milestone(tenant_id,id),
 CONSTRAINT fk_performance_evidence_author FOREIGN KEY(tenant_id,recorded_by)
    REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_performance_evidence_milestone ON performance_milestone_evidence(tenant_id,milestone_id,recorded_at DESC);
ALTER TABLE performance_plan_milestone ENABLE ROW LEVEL SECURITY;
ALTER TABLE performance_plan_milestone FORCE ROW LEVEL SECURITY;
CREATE POLICY performance_milestone_tenant ON performance_plan_milestone
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
ALTER TABLE performance_milestone_evidence ENABLE ROW LEVEL SECURITY;
ALTER TABLE performance_milestone_evidence FORCE ROW LEVEL SECURITY;
CREATE POLICY performance_evidence_tenant ON performance_milestone_evidence
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
