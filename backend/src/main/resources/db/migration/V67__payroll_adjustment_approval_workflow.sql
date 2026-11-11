INSERT INTO approval_process_type(code,module,display_name)
VALUES ('PAYROLL_ADJUSTMENT','PAYROLL','Payroll adjustment');

ALTER TABLE adjustment
    ADD COLUMN approval_instance_id uuid;

ALTER TABLE adjustment
    ADD CONSTRAINT fk_adjustment_approval_instance
        FOREIGN KEY (tenant_id, approval_instance_id)
        REFERENCES approval_instance (tenant_id, id);

CREATE UNIQUE INDEX uq_adjustment_approval_instance
    ON adjustment (tenant_id, approval_instance_id)
    WHERE approval_instance_id IS NOT NULL;
