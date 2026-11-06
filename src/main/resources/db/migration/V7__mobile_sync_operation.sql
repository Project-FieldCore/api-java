-- Idempotency receipt for mobile sync push. One row per operation_id so a retry
-- returns the previous result and does not apply the write again.

CREATE TABLE mobile_sync_operation (
    operation_id UUID PRIMARY KEY,
    device_id UUID NOT NULL,
    technician_id UUID NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id UUID NOT NULL,
    operation_type VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    entity_version INTEGER,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mobile_sync_operation_technician FOREIGN KEY (technician_id) REFERENCES app_user (id),
    CONSTRAINT ck_mobile_sync_operation_status CHECK (status IN ('APPLIED', 'REJECTED', 'CONFLICT', 'DEPENDENCY_FAILED')),
    CONSTRAINT ck_mobile_sync_operation_version CHECK (entity_version IS NULL OR entity_version >= 0)
);

CREATE INDEX ix_mobile_sync_operation_technician_id ON mobile_sync_operation (technician_id);
