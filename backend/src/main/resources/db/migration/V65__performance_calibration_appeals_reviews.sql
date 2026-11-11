ALTER TABLE performance_review
 ADD COLUMN final_rating numeric(3,2),
 ADD CONSTRAINT ck_performance_review_final_rating
   CHECK(final_rating IS NULL OR (final_rating >= 1 AND final_rating <= 5));

CREATE TABLE performance_review_calibration (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 review_id uuid NOT NULL,
 previous_rating numeric(3,2) NOT NULL,
 calibrated_rating numeric(3,2) NOT NULL,
 reason varchar(4000) NOT NULL,
 source varchar(24) NOT NULL DEFAULT 'CALIBRATION',
 calibrated_by uuid NOT NULL,
 calibrated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_performance_calibration_review FOREIGN KEY(tenant_id,review_id) REFERENCES performance_review(tenant_id,id),
 CONSTRAINT fk_performance_calibration_actor FOREIGN KEY(tenant_id,calibrated_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_performance_calibration_previous CHECK(previous_rating BETWEEN 1 AND 5),
 CONSTRAINT ck_performance_calibration_rating CHECK(calibrated_rating BETWEEN 1 AND 5),
 CONSTRAINT ck_performance_calibration_source CHECK(source IN ('CALIBRATION','APPEAL'))
);
CREATE INDEX ix_performance_calibration_review ON performance_review_calibration(tenant_id,review_id,calibrated_at DESC);

CREATE TABLE performance_review_approval (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 review_id uuid NOT NULL,
 approver_id uuid NOT NULL,
 assigned_by uuid NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'PENDING',
 decision_note varchar(2000),
 assigned_at timestamptz NOT NULL DEFAULT now(),
 decided_at timestamptz,
 CONSTRAINT uq_performance_review_approval UNIQUE(tenant_id,review_id,approver_id),
 CONSTRAINT fk_performance_approval_review FOREIGN KEY(tenant_id,review_id) REFERENCES performance_review(tenant_id,id),
 CONSTRAINT fk_performance_approval_approver FOREIGN KEY(tenant_id,approver_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_performance_approval_assigner FOREIGN KEY(tenant_id,assigned_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_performance_approval_status CHECK(status IN ('PENDING','APPROVED')),
 CONSTRAINT ck_performance_approval_decision CHECK((status='PENDING' AND decided_at IS NULL) OR (status='APPROVED' AND decided_at IS NOT NULL))
);
CREATE INDEX ix_performance_review_approval_inbox ON performance_review_approval(tenant_id,approver_id,status,assigned_at DESC);

CREATE TABLE performance_review_appeal (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 review_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 reason varchar(10000) NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'OPEN',
 resolution varchar(4000),
 adjusted_rating numeric(3,2),
 decided_by uuid,
 created_at timestamptz NOT NULL DEFAULT now(),
 decided_at timestamptz,
 CONSTRAINT uq_performance_review_appeal UNIQUE(tenant_id,review_id),
 CONSTRAINT fk_performance_appeal_review FOREIGN KEY(tenant_id,review_id) REFERENCES performance_review(tenant_id,id),
 CONSTRAINT fk_performance_appeal_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_performance_appeal_decider FOREIGN KEY(tenant_id,decided_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_performance_appeal_status CHECK(status IN ('OPEN','UPHELD','ADJUSTED','REJECTED')),
 CONSTRAINT ck_performance_appeal_adjusted_rating CHECK(adjusted_rating IS NULL OR (adjusted_rating >= 1 AND adjusted_rating <= 5)),
 CONSTRAINT ck_performance_appeal_decision CHECK((status='OPEN' AND decided_by IS NULL AND decided_at IS NULL) OR (status<>'OPEN' AND decided_by IS NOT NULL AND decided_at IS NOT NULL)),
 CONSTRAINT ck_performance_appeal_adjustment CHECK((status='ADJUSTED') = (adjusted_rating IS NOT NULL))
);
CREATE INDEX ix_performance_appeal_queue ON performance_review_appeal(tenant_id,status,created_at DESC);

DO $$
DECLARE t text;
BEGIN
 FOREACH t IN ARRAY ARRAY['performance_review_calibration','performance_review_approval','performance_review_appeal']
 LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',t);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',t);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id())',t || '_tenant',t);
 END LOOP;
END $$;
