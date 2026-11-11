INSERT INTO approval_process_type(code,module,display_name)
VALUES ('COMPENSATION_CHANGE','PERSONNEL','Compensation change recommendation');

CREATE TABLE performance_compensation_recommendation (
 id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
 tenant_id uuid NOT NULL REFERENCES tenant(id),
 review_id uuid NOT NULL,
 employee_id uuid NOT NULL,
 current_amount numeric(15,2) NOT NULL,
 proposed_amount numeric(15,2) NOT NULL,
 currency_code char(3) NOT NULL DEFAULT 'PHP',
 pay_basis varchar(20) NOT NULL,
 effective_date date NOT NULL,
 reason varchar(4000) NOT NULL,
 status varchar(20) NOT NULL DEFAULT 'PENDING',
 approval_instance_id uuid,
 created_by uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 decided_at timestamptz,
 CONSTRAINT uq_perf_comp_rec_tenant UNIQUE(tenant_id,id),
 CONSTRAINT uq_perf_comp_rec_review UNIQUE(tenant_id,review_id),
 CONSTRAINT fk_perf_comp_rec_review FOREIGN KEY(tenant_id,review_id) REFERENCES performance_review(tenant_id,id),
 CONSTRAINT fk_perf_comp_rec_employee FOREIGN KEY(tenant_id,employee_id) REFERENCES personnel_employee(tenant_id,id),
 CONSTRAINT fk_perf_comp_rec_author FOREIGN KEY(tenant_id,created_by) REFERENCES auth_identity(tenant_id,id),
 CONSTRAINT fk_perf_comp_rec_approval FOREIGN KEY(tenant_id,approval_instance_id) REFERENCES approval_instance(tenant_id,id),
 CONSTRAINT ck_perf_comp_rec_amounts CHECK(current_amount>0 AND proposed_amount>current_amount),
 CONSTRAINT ck_perf_comp_rec_basis CHECK(pay_basis IN ('MONTHLY','DAILY','HOURLY')),
 CONSTRAINT ck_perf_comp_rec_status CHECK(status IN ('PENDING','APPROVED','REJECTED'))
);
CREATE INDEX ix_perf_comp_rec_queue ON performance_compensation_recommendation(tenant_id,status,created_at DESC);
ALTER TABLE performance_compensation_recommendation ENABLE ROW LEVEL SECURITY;
ALTER TABLE performance_compensation_recommendation FORCE ROW LEVEL SECURITY;
CREATE POLICY performance_comp_rec_tenant ON performance_compensation_recommendation
 USING(tenant_id=app_tenant_id()) WITH CHECK(tenant_id=app_tenant_id());
