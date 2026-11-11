ALTER TABLE time_log ADD COLUMN location_accuracy_m numeric(10,2);
ALTER TABLE time_log ADD CONSTRAINT uq_time_log_tenant_id_id UNIQUE (tenant_id,id);

CREATE TABLE attendance_photo (
    id uuid PRIMARY KEY,
    tenant_id uuid NOT NULL REFERENCES tenant(id),
    employee_id uuid NOT NULL,
    time_log_id uuid NOT NULL,
    content_type varchar(40) NOT NULL DEFAULT 'image/jpeg',
    photo_bytes bytea NOT NULL,
    captured_at timestamptz NOT NULL DEFAULT now(),
    captured_by uuid,
    UNIQUE (tenant_id,id),
    UNIQUE (tenant_id,time_log_id),
    FOREIGN KEY (tenant_id,employee_id) REFERENCES employee(tenant_id,id),
    FOREIGN KEY (tenant_id,time_log_id) REFERENCES time_log(tenant_id,id)
);
CREATE INDEX idx_attendance_photo_employee ON attendance_photo(tenant_id,employee_id,captured_at DESC);

ALTER TABLE attendance_photo ENABLE ROW LEVEL SECURITY;
ALTER TABLE attendance_photo FORCE ROW LEVEL SECURITY;
CREATE POLICY attendance_photo_tenant ON attendance_photo
    USING (tenant_id=app_tenant_id()) WITH CHECK (tenant_id=app_tenant_id());
