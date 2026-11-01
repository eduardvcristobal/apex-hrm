-- M4 recruitment workspace: applicant data is tenant isolated and every stage change is auditable.
CREATE TABLE recruitment_job (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), company_id uuid NOT NULL,
 code varchar(50) NOT NULL, title varchar(180) NOT NULL, department varchar(160), location varchar(160), employment_type varchar(40) NOT NULL DEFAULT 'FULL_TIME',
 description text NOT NULL DEFAULT '', status varchar(20) NOT NULL DEFAULT 'DRAFT', openings integer NOT NULL DEFAULT 1,
 target_date date, created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_recruitment_job_tenant_id UNIQUE(tenant_id,id), CONSTRAINT uq_recruitment_job_code UNIQUE(tenant_id,code),
 CONSTRAINT fk_recruitment_job_company FOREIGN KEY(tenant_id,company_id) REFERENCES company(tenant_id,id),
 CONSTRAINT fk_recruitment_job_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_recruitment_job_status CHECK(status IN ('DRAFT','OPEN','ON_HOLD','CLOSED','CANCELLED')),
 CONSTRAINT ck_recruitment_job_openings CHECK(openings > 0)
);
CREATE TABLE recruitment_candidate (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), first_name varchar(120) NOT NULL,
 last_name varchar(120) NOT NULL, email varchar(254) NOT NULL, phone varchar(50), source varchar(100), resume_url text,
 notes text NOT NULL DEFAULT '', created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_recruitment_candidate_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_recruitment_candidate_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_recruitment_candidate_email ON recruitment_candidate(tenant_id,lower(email));
CREATE TABLE recruitment_application (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), job_id uuid NOT NULL, candidate_id uuid NOT NULL,
 stage varchar(24) NOT NULL DEFAULT 'NEW', applied_on date NOT NULL DEFAULT current_date, rating smallint, disposition_reason text,
 owner_id uuid, created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_recruitment_application_tenant_id UNIQUE(tenant_id,id), CONSTRAINT uq_recruitment_application_candidate_job UNIQUE(tenant_id,job_id,candidate_id),
 CONSTRAINT fk_recruitment_app_job FOREIGN KEY(tenant_id,job_id) REFERENCES recruitment_job(tenant_id,id),
 CONSTRAINT fk_recruitment_app_candidate FOREIGN KEY(tenant_id,candidate_id) REFERENCES recruitment_candidate(tenant_id,id),
 CONSTRAINT fk_recruitment_app_owner FOREIGN KEY(tenant_id,owner_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_recruitment_app_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_recruitment_app_stage CHECK(stage IN ('NEW','SCREENING','INTERVIEW','ASSESSMENT','OFFER','HIRED','REJECTED','WITHDRAWN')),
 CONSTRAINT ck_recruitment_app_rating CHECK(rating IS NULL OR rating BETWEEN 1 AND 5)
);
CREATE INDEX ix_recruitment_application_job_stage ON recruitment_application(tenant_id,job_id,stage,created_at DESC);
CREATE TABLE recruitment_interview (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), application_id uuid NOT NULL,
 interviewer_id uuid NOT NULL, scheduled_at timestamptz NOT NULL, duration_minutes integer NOT NULL DEFAULT 60, interview_type varchar(24) NOT NULL DEFAULT 'VIDEO',
 location text, feedback text, rating smallint, status varchar(20) NOT NULL DEFAULT 'SCHEDULED', created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_recruitment_interview_tenant_id UNIQUE(tenant_id,id),
 CONSTRAINT fk_recruitment_interview_application FOREIGN KEY(tenant_id,application_id) REFERENCES recruitment_application(tenant_id,id),
 CONSTRAINT fk_recruitment_interview_interviewer FOREIGN KEY(tenant_id,interviewer_id) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_recruitment_interview_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_recruitment_interview_type CHECK(interview_type IN ('VIDEO','PHONE','ONSITE','OTHER')),
 CONSTRAINT ck_recruitment_interview_status CHECK(status IN ('SCHEDULED','COMPLETED','CANCELLED')),
 CONSTRAINT ck_recruitment_interview_duration CHECK(duration_minutes BETWEEN 15 AND 480),
 CONSTRAINT ck_recruitment_interview_rating CHECK(rating IS NULL OR rating BETWEEN 1 AND 5)
);
CREATE TABLE recruitment_offer (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), application_id uuid NOT NULL,
 offered_title varchar(180) NOT NULL, annual_salary numeric(14,2) NOT NULL, currency char(3) NOT NULL DEFAULT 'PHP', start_date date,
 expires_on date, status varchar(20) NOT NULL DEFAULT 'DRAFT', notes text NOT NULL DEFAULT '', created_by uuid NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT uq_recruitment_offer_tenant_id UNIQUE(tenant_id,id), CONSTRAINT uq_recruitment_offer_application UNIQUE(tenant_id,application_id),
 CONSTRAINT fk_recruitment_offer_application FOREIGN KEY(tenant_id,application_id) REFERENCES recruitment_application(tenant_id,id),
 CONSTRAINT fk_recruitment_offer_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT ck_recruitment_offer_status CHECK(status IN ('DRAFT','SENT','ACCEPTED','DECLINED','EXPIRED','WITHDRAWN')),
 CONSTRAINT ck_recruitment_offer_salary CHECK(annual_salary >= 0),
 CONSTRAINT ck_recruitment_offer_dates CHECK(expires_on IS NULL OR start_date IS NULL OR expires_on <= start_date)
);
CREATE TABLE recruitment_event (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(), tenant_id uuid NOT NULL REFERENCES tenant(id), application_id uuid NOT NULL,
 actor_id uuid NOT NULL, event_type varchar(40) NOT NULL, from_stage varchar(24), to_stage varchar(24), details jsonb NOT NULL DEFAULT '{}'::jsonb, created_at timestamptz NOT NULL DEFAULT now(),
 CONSTRAINT fk_recruitment_event_application FOREIGN KEY(tenant_id,application_id) REFERENCES recruitment_application(tenant_id,id),
 CONSTRAINT fk_recruitment_event_actor FOREIGN KEY(tenant_id,actor_id) REFERENCES auth_identity(tenant_id,id)
);
CREATE INDEX ix_recruitment_event_application ON recruitment_event(tenant_id,application_id,created_at DESC);
DO $$ DECLARE tab text; BEGIN FOREACH tab IN ARRAY ARRAY['recruitment_job','recruitment_candidate','recruitment_application','recruitment_interview','recruitment_offer','recruitment_event'] LOOP EXECUTE format('ALTER TABLE %I ENABLE ROW LEVEL SECURITY',tab); EXECUTE format('ALTER TABLE %I FORCE ROW LEVEL SECURITY',tab); EXECUTE format('CREATE POLICY %I ON %I USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id())',tab||'_tenant',tab); END LOOP; END $$;
