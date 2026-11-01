-- Keep employee portal account links in one source of truth. Existing learning links are migrated forward.
INSERT INTO ld_employee_account(tenant_id,identity_id,employee_id,linked_by)
SELECT p.tenant_id,p.identity_id,p.employee_id,p.linked_by FROM performance_employee_identity p
WHERE NOT EXISTS (SELECT 1 FROM ld_employee_account l
 WHERE l.tenant_id=p.tenant_id AND (l.identity_id=p.identity_id OR l.employee_id=p.employee_id))
ON CONFLICT DO NOTHING;
