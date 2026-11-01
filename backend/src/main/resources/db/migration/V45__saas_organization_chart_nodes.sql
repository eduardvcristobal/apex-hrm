CREATE TABLE organization_chart_node (
    id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    company_id uuid NOT NULL,
    parent_id uuid,
    node_type varchar(24) NOT NULL,
    name varchar(200) NOT NULL,
    title varchar(160),
    employee_id uuid,
    sort_order integer NOT NULL DEFAULT 0,
    active boolean NOT NULL DEFAULT true,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT ck_org_chart_node_type CHECK (node_type IN ('OWNER','CEO','EXECUTIVE','DEPARTMENT','SECTION','TEAM','POSITION','OTHER')),
    CONSTRAINT ck_org_chart_root_owner CHECK ((parent_id IS NULL AND node_type='OWNER') OR (parent_id IS NOT NULL AND node_type<>'OWNER')),
    CONSTRAINT uq_org_chart_tenant_company_id UNIQUE (tenant_id,company_id,id),
    CONSTRAINT fk_org_chart_company FOREIGN KEY (tenant_id,company_id) REFERENCES company(tenant_id,id),
    CONSTRAINT fk_org_chart_parent FOREIGN KEY (tenant_id,company_id,parent_id) REFERENCES organization_chart_node(tenant_id,company_id,id),
    CONSTRAINT fk_org_chart_employee FOREIGN KEY (tenant_id,company_id,employee_id) REFERENCES personnel_employee(tenant_id,company_id,id)
);
CREATE UNIQUE INDEX uq_org_chart_owner ON organization_chart_node(tenant_id,company_id) WHERE node_type='OWNER';
CREATE UNIQUE INDEX uq_org_chart_ceo ON organization_chart_node(tenant_id,company_id) WHERE node_type='CEO';
CREATE INDEX ix_org_chart_parent ON organization_chart_node(tenant_id,company_id,parent_id,sort_order);
ALTER TABLE organization_chart_node ENABLE ROW LEVEL SECURITY;
ALTER TABLE organization_chart_node FORCE ROW LEVEL SECURITY;
CREATE POLICY org_chart_tenant_policy ON organization_chart_node
    USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
