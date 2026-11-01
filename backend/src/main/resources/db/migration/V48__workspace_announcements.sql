CREATE TABLE announcement (
  id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
  tenant_id uuid NOT NULL REFERENCES tenant(id),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  version bigint NOT NULL DEFAULT 0,
  author_id uuid NOT NULL REFERENCES auth_identity(id),
  title varchar(200) NOT NULL,
  body text NOT NULL,
  priority varchar(16) NOT NULL DEFAULT 'NORMAL',
  status varchar(16) NOT NULL DEFAULT 'DRAFT',
  published_at timestamptz,
  expires_on date,
  pinned boolean NOT NULL DEFAULT false,
  CONSTRAINT ck_announcement_priority CHECK (priority IN ('NORMAL','IMPORTANT','URGENT')),
  CONSTRAINT ck_announcement_status CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
  CONSTRAINT ck_announcement_body CHECK (length(btrim(body)) > 0)
);

CREATE INDEX idx_announcement_tenant_status_published
  ON announcement(tenant_id, status, published_at DESC);
CREATE INDEX idx_announcement_tenant_expiry
  ON announcement(tenant_id, expires_on);
