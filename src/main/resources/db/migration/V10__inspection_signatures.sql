CREATE TABLE inspection_signature (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    signed_by UUID NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inspection_signature_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id) ON DELETE CASCADE,
    CONSTRAINT fk_inspection_signature_signed_by FOREIGN KEY (signed_by) REFERENCES app_user (id),
    CONSTRAINT uk_inspection_signature_inspection UNIQUE (inspection_id),
    CONSTRAINT uk_inspection_signature_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_inspection_signature_size CHECK (size_bytes > 0)
);
