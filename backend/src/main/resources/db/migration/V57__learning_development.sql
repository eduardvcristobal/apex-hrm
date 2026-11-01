-- M6.1: Learning & Development foundation. Reserved V80 to avoid concurrent M3/M5 migrations.
CREATE TABLE ld_course (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 company_id uuid NOT NULL,
 code varchar(50) NOT NULL,
 title varchar(200) NOT NULL,
 description text NOT NULL DEFAULT '',
 delivery_mode varchar(16) NOT NULL DEFAULT 'ONLINE',
 duration_hours numeric(8,2) NOT NULL DEFAULT 0,
 active boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_ld_course_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_ld_course_code UNIQUE(tenant_id,company_id,code),
 CONSTRAINT fk_ld_course_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT ck_ld_course_mode CHECK(delivery_mode IN ('ONLINE','CLASSROOM','BLENDED','SELF_PACED')),
 CONSTRAINT ck_ld_course_duration CHECK(duration_hours >= 0)
);
CREATE TABLE ld_enrollment (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 course_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 status varchar(16) NOT NULL DEFAULT 'ENROLLED',
 enrolled_on date NOT NULL DEFAULT current_date,
 completed_on date,
 score numeric(5,2),
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_ld_enrollment_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_ld_enrollment_course_employee UNIQUE(tenant_id,course_id,employee_id),
 CONSTRAINT fk_ld_enrollment_course FOREIGN KEY(tenant_id,course_id) REFERENCES ld_course(tenant_id,id),
 CONSTRAINT fk_ld_enrollment_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT ck_ld_enrollment_status CHECK(status IN ('ENROLLED','IN_PROGRESS','COMPLETED','CANCELLED')),
 CONSTRAINT ck_ld_enrollment_completion CHECK((status = 'COMPLETED') = (completed_on IS NOT NULL)),
 CONSTRAINT ck_ld_enrollment_score CHECK(score IS NULL OR (score >= 0 AND score <= 100)),
 CONSTRAINT ck_ld_enrollment_dates CHECK(completed_on IS NULL OR completed_on >= enrolled_on)
);
CREATE INDEX ix_ld_enrollment_employee ON ld_enrollment(tenant_id,employee_id,completed_on DESC);
CREATE TABLE ld_skill (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 name varchar(120) NOT NULL,
 category varchar(100),
 CONSTRAINT uq_ld_skill_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT uq_ld_skill_name UNIQUE(tenant_id,name)
);
CREATE TABLE ld_employee_skill (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 skill_id uuid NOT NULL,
 level smallint NOT NULL,
 assessed_on date NOT NULL DEFAULT current_date,
 CONSTRAINT uq_ld_employee_skill UNIQUE(tenant_id,employee_id,skill_id),
 CONSTRAINT fk_ld_es_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_ld_es_skill FOREIGN KEY(tenant_id,skill_id) REFERENCES ld_skill(tenant_id,id),
 CONSTRAINT ck_ld_es_level CHECK(level BETWEEN 1 AND 5)
);
CREATE TABLE ld_certification (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 employee_id uuid NOT NULL,
 course_id uuid,
 title varchar(200) NOT NULL,
 issuer varchar(160) NOT NULL,
 credential_ref varchar(160),
 issued_on date NOT NULL,
 expires_on date,
 created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_ld_cert_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_ld_cert_course FOREIGN KEY(tenant_id,course_id) REFERENCES ld_course(tenant_id,id),
 CONSTRAINT ck_ld_cert_dates CHECK(expires_on IS NULL OR expires_on >= issued_on)
);
CREATE INDEX ix_ld_cert_employee_expiry ON ld_certification(tenant_id,employee_id,expires_on);
DO $$
DECLARE tab text;
BEGIN
 FOREACH tab IN ARRAY ARRAY['ld_course','ld_enrollment','ld_skill','ld_employee_skill','ld_certification'] LOOP
  EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab);
  EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab);
  EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id())',tab||'_tenant',tab);
 END LOOP;
END $$;
