CREATE TABLE tenant_attendance_evidence_policy (
  tenant_id uuid PRIMARY KEY REFERENCES tenant(id),
  retention_days integer,
  enabled boolean NOT NULL DEFAULT false,
  rationale varchar(500),
  updated_by uuid,
  updated_at timestamptz NOT NULL DEFAULT now(),
  CONSTRAINT ck_attendance_evidence_retention_days CHECK (retention_days IS NULL OR retention_days BETWEEN 1 AND 3650),
  CONSTRAINT ck_attendance_evidence_retention_enabled CHECK (NOT enabled OR retention_days IS NOT NULL),
  FOREIGN KEY (tenant_id,updated_by) REFERENCES auth_identity(tenant_id,id)
);

ALTER TABLE attendance_photo ADD COLUMN legal_hold boolean NOT NULL DEFAULT false;
ALTER TABLE attendance_photo ADD COLUMN legal_hold_reason varchar(500);
ALTER TABLE attendance_photo ADD COLUMN legal_hold_updated_by uuid;
ALTER TABLE attendance_photo ADD COLUMN legal_hold_updated_at timestamptz;
ALTER TABLE attendance_photo ADD CONSTRAINT fk_attendance_photo_hold_actor
  FOREIGN KEY (tenant_id,legal_hold_updated_by) REFERENCES auth_identity(tenant_id,id);
