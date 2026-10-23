-- Immutable template versions and the inspection execution record.

CREATE TABLE inspection_template (
    id UUID PRIMARY KEY,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inspection_template_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT uk_inspection_template_name UNIQUE (name)
);

CREATE TABLE inspection_template_version (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL,
    version INTEGER NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    created_by UUID,
    published_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_template_version_template FOREIGN KEY (template_id) REFERENCES inspection_template (id) ON DELETE CASCADE,
    CONSTRAINT fk_template_version_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT uk_inspection_template_version UNIQUE (template_id, version),
    CONSTRAINT ck_template_version_positive CHECK (version > 0),
    CONSTRAINT ck_template_version_status CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED'))
);

CREATE INDEX ix_template_version_template_id ON inspection_template_version (template_id);

CREATE TABLE template_section (
    id UUID PRIMARY KEY,
    template_version_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    display_order INTEGER NOT NULL,
    CONSTRAINT fk_template_section_version FOREIGN KEY (template_version_id) REFERENCES inspection_template_version (id) ON DELETE CASCADE,
    CONSTRAINT uk_template_section_order UNIQUE (template_version_id, display_order),
    CONSTRAINT ck_template_section_order CHECK (display_order >= 0)
);

CREATE TABLE template_item (
    id UUID PRIMARY KEY,
    section_id UUID NOT NULL,
    label VARCHAR(500) NOT NULL,
    help_text VARCHAR(1000),
    response_type VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL,
    configuration TEXT,
    CONSTRAINT fk_template_item_section FOREIGN KEY (section_id) REFERENCES template_section (id) ON DELETE CASCADE,
    CONSTRAINT uk_template_item_order UNIQUE (section_id, display_order),
    CONSTRAINT ck_template_item_order CHECK (display_order >= 0),
    CONSTRAINT ck_template_item_response_type CHECK (response_type IN ('BOOLEAN', 'TEXT', 'NUMBER', 'SELECT', 'PHOTO'))
);

CREATE INDEX ix_template_item_section_id ON template_item (section_id);

CREATE TABLE inspection (
    id UUID PRIMARY KEY,
    site_id UUID NOT NULL,
    equipment_id UUID NOT NULL,
    template_version_id UUID NOT NULL,
    technician_id UUID NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    scheduled_at TIMESTAMP WITH TIME ZONE,
    started_at TIMESTAMP WITH TIME ZONE,
    completed_at TIMESTAMP WITH TIME ZONE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inspection_site FOREIGN KEY (site_id) REFERENCES inspection_site (id),
    CONSTRAINT fk_inspection_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id),
    CONSTRAINT fk_inspection_template_version FOREIGN KEY (template_version_id) REFERENCES inspection_template_version (id),
    CONSTRAINT fk_inspection_technician FOREIGN KEY (technician_id) REFERENCES app_user (id),
    CONSTRAINT ck_inspection_status CHECK (status IN ('DRAFT', 'IN_PROGRESS', 'COMPLETED', 'REVIEWED', 'CANCELLED'))
);

CREATE INDEX ix_inspection_equipment_id ON inspection (equipment_id);
CREATE INDEX ix_inspection_technician_id ON inspection (technician_id);
CREATE INDEX ix_inspection_status ON inspection (status);

CREATE TABLE inspection_item_snapshot (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    template_item_id UUID NOT NULL,
    section_title VARCHAR(255) NOT NULL,
    label VARCHAR(500) NOT NULL,
    response_type VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL,
    display_order INTEGER NOT NULL,
    configuration TEXT,
    CONSTRAINT fk_snapshot_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id) ON DELETE CASCADE,
    CONSTRAINT fk_snapshot_template_item FOREIGN KEY (template_item_id) REFERENCES template_item (id),
    CONSTRAINT uk_snapshot_inspection_template_item UNIQUE (inspection_id, template_item_id)
);

CREATE INDEX ix_snapshot_inspection_id ON inspection_item_snapshot (inspection_id);

CREATE TABLE inspection_response (
    id UUID PRIMARY KEY,
    inspection_item_id UUID NOT NULL,
    value_text TEXT,
    value_number DECIMAL(18,4),
    value_boolean BOOLEAN,
    selected_option VARCHAR(255),
    answered_at TIMESTAMP WITH TIME ZONE,
    answered_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inspection_response_item FOREIGN KEY (inspection_item_id) REFERENCES inspection_item_snapshot (id) ON DELETE CASCADE,
    CONSTRAINT fk_inspection_response_answered_by FOREIGN KEY (answered_by) REFERENCES app_user (id),
    CONSTRAINT uk_inspection_response_item UNIQUE (inspection_item_id)
);

CREATE TABLE evidence (
    id UUID PRIMARY KEY,
    inspection_response_id UUID NOT NULL,
    storage_key VARCHAR(1024) NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(127) NOT NULL,
    size_bytes BIGINT NOT NULL,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_evidence_response FOREIGN KEY (inspection_response_id) REFERENCES inspection_response (id) ON DELETE CASCADE,
    CONSTRAINT fk_evidence_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT uk_evidence_storage_key UNIQUE (storage_key),
    CONSTRAINT ck_evidence_size_bytes CHECK (size_bytes >= 0)
);

CREATE INDEX ix_evidence_response_id ON evidence (inspection_response_id);

CREATE TABLE non_conformity (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    inspection_response_id UUID,
    severity VARCHAR(32) NOT NULL,
    description VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OPEN',
    resolved_at TIMESTAMP WITH TIME ZONE,
    resolved_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_non_conformity_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id) ON DELETE CASCADE,
    CONSTRAINT fk_non_conformity_response FOREIGN KEY (inspection_response_id) REFERENCES inspection_response (id),
    CONSTRAINT fk_non_conformity_resolved_by FOREIGN KEY (resolved_by) REFERENCES app_user (id),
    CONSTRAINT ck_non_conformity_severity CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_non_conformity_status CHECK (status IN ('OPEN', 'RESOLVED', 'WAIVED'))
);

CREATE INDEX ix_non_conformity_inspection_id ON non_conformity (inspection_id);

CREATE TABLE inspection_review (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    reviewer_id UUID NOT NULL,
    decision VARCHAR(32) NOT NULL,
    comment VARCHAR(2000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inspection_review_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id) ON DELETE CASCADE,
    CONSTRAINT fk_inspection_review_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user (id),
    CONSTRAINT uk_inspection_review_inspection UNIQUE (inspection_id),
    CONSTRAINT ck_inspection_review_decision CHECK (decision IN ('APPROVED', 'REJECTED'))
);

CREATE TABLE audit_event (
    id UUID PRIMARY KEY,
    actor_id UUID,
    entity_type VARCHAR(100) NOT NULL,
    entity_id UUID,
    action VARCHAR(100) NOT NULL,
    payload TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_audit_event_actor FOREIGN KEY (actor_id) REFERENCES app_user (id)
);

CREATE INDEX ix_audit_event_entity ON audit_event (entity_type, entity_id);

CREATE TABLE sync_idempotency (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    idempotency_key VARCHAR(255) NOT NULL,
    response_status INTEGER,
    response_body TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_sync_idempotency_user FOREIGN KEY (user_id) REFERENCES app_user (id) ON DELETE CASCADE,
    CONSTRAINT uk_sync_idempotency_user_key UNIQUE (user_id, idempotency_key)
);

CREATE INDEX ix_sync_idempotency_expires_at ON sync_idempotency (expires_at);
