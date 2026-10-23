-- Immutable template versions and the inspection execution record.

CREATE TABLE inspection_template (
    id UUID PRIMARY KEY,
    title VARCHAR(160) NOT NULL,
    description VARCHAR(1000),
    category VARCHAR(100) NOT NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    current_version INTEGER,
    created_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_inspection_template_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_inspection_template_title UNIQUE (title),
    CONSTRAINT ck_inspection_template_status CHECK (status IN ('DRAFT', 'ACTIVE', 'INACTIVE')),
    CONSTRAINT ck_inspection_template_version CHECK (version >= 0)
);

CREATE TABLE inspection_template_version (
    id UUID PRIMARY KEY,
    template_id UUID NOT NULL,
    version_number INTEGER NOT NULL,
    title_snapshot VARCHAR(160) NOT NULL,
    description_snapshot VARCHAR(1000),
    published_by UUID,
    published_at TIMESTAMP WITH TIME ZONE,
    active_for_new_inspections BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_template_version_template FOREIGN KEY (template_id) REFERENCES inspection_template (id) ON DELETE CASCADE,
    CONSTRAINT fk_template_version_published_by FOREIGN KEY (published_by) REFERENCES app_user (id),
    CONSTRAINT uk_inspection_template_version UNIQUE (template_id, version_number),
    CONSTRAINT ck_template_version_positive CHECK (version_number > 0)
);

CREATE INDEX ix_template_version_template_id ON inspection_template_version (template_id);

CREATE TABLE template_section (
    id UUID PRIMARY KEY,
    template_version_id UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    description VARCHAR(1000),
    display_order INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_template_section_version FOREIGN KEY (template_version_id) REFERENCES inspection_template_version (id) ON DELETE CASCADE,
    CONSTRAINT uk_template_section_order UNIQUE (template_version_id, display_order),
    CONSTRAINT ck_template_section_order CHECK (display_order >= 0)
);

CREATE TABLE template_item (
    id UUID PRIMARY KEY,
    section_id UUID NOT NULL,
    code VARCHAR(100),
    title VARCHAR(500) NOT NULL,
    description VARCHAR(1000),
    response_type VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL DEFAULT FALSE,
    observation_required_on_failure BOOLEAN NOT NULL DEFAULT FALSE,
    evidence_required_on_failure BOOLEAN NOT NULL DEFAULT FALSE,
    display_order INTEGER NOT NULL,
    options_json TEXT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_template_item_section FOREIGN KEY (section_id) REFERENCES template_section (id) ON DELETE CASCADE,
    CONSTRAINT uk_template_item_order UNIQUE (section_id, display_order),
    CONSTRAINT ck_template_item_order CHECK (display_order >= 0),
    CONSTRAINT ck_template_item_response_type CHECK (response_type IN ('TEXT_SHORT', 'TEXT_LONG', 'NUMBER', 'BOOLEAN', 'CONFORMITY', 'SINGLE_CHOICE', 'DATE'))
);

CREATE INDEX ix_template_item_section_id ON template_item (section_id);

CREATE TABLE inspection (
    id UUID PRIMARY KEY,
    client_id UUID NOT NULL,
    site_id UUID NOT NULL,
    equipment_id UUID,
    template_version_id UUID NOT NULL,
    technician_id UUID NOT NULL,
    supervisor_id UUID,
    created_by UUID NOT NULL,
    title VARCHAR(255) NOT NULL,
    instructions VARCHAR(2000),
    priority VARCHAR(32) NOT NULL DEFAULT 'MEDIUM',
    status VARCHAR(32) NOT NULL DEFAULT 'DRAFT',
    scheduled_for TIMESTAMP WITH TIME ZONE,
    started_at_device TIMESTAMP WITH TIME ZONE,
    started_at_server TIMESTAMP WITH TIME ZONE,
    completed_at_device TIMESTAMP WITH TIME ZONE,
    submitted_at_server TIMESTAMP WITH TIME ZONE,
    approved_at TIMESTAMP WITH TIME ZONE,
    canceled_at TIMESTAMP WITH TIME ZONE,
    canceled_by UUID,
    canceled_reason VARCHAR(1000),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_inspection_site FOREIGN KEY (site_id, client_id) REFERENCES inspection_site (id, client_id),
    CONSTRAINT fk_inspection_equipment FOREIGN KEY (equipment_id, site_id) REFERENCES equipment (id, site_id),
    CONSTRAINT fk_inspection_client FOREIGN KEY (client_id) REFERENCES client (id),
    CONSTRAINT fk_inspection_template_version FOREIGN KEY (template_version_id) REFERENCES inspection_template_version (id),
    CONSTRAINT fk_inspection_technician FOREIGN KEY (technician_id) REFERENCES app_user (id),
    CONSTRAINT fk_inspection_supervisor FOREIGN KEY (supervisor_id) REFERENCES app_user (id),
    CONSTRAINT fk_inspection_created_by FOREIGN KEY (created_by) REFERENCES app_user (id),
    CONSTRAINT fk_inspection_canceled_by FOREIGN KEY (canceled_by) REFERENCES app_user (id),
    CONSTRAINT ck_inspection_priority CHECK (priority IN ('LOW', 'MEDIUM', 'HIGH', 'CRITICAL')),
    CONSTRAINT ck_inspection_status CHECK (status IN ('DRAFT', 'ASSIGNED', 'IN_PROGRESS', 'SUBMITTED', 'UNDER_REVIEW', 'APPROVED', 'REJECTED', 'CANCELED')),
    CONSTRAINT ck_inspection_version CHECK (version >= 0)
);

CREATE INDEX ix_inspection_equipment_id ON inspection (equipment_id);
CREATE INDEX ix_inspection_technician_id ON inspection (technician_id);
CREATE INDEX ix_inspection_technician_status ON inspection (technician_id, status);
CREATE INDEX ix_inspection_supervisor_status ON inspection (supervisor_id, status);
CREATE INDEX ix_inspection_client_id ON inspection (client_id);
CREATE INDEX ix_inspection_status ON inspection (status);

CREATE TABLE inspection_item_snapshot (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    source_template_item_id UUID,
    section_title VARCHAR(255) NOT NULL,
    section_description VARCHAR(1000),
    section_order INTEGER NOT NULL,
    item_code VARCHAR(100),
    item_title VARCHAR(500) NOT NULL,
    item_description VARCHAR(1000),
    response_type VARCHAR(32) NOT NULL,
    required BOOLEAN NOT NULL,
    rules_json TEXT,
    options_json TEXT,
    item_order INTEGER NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_snapshot_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id) ON DELETE CASCADE,
    CONSTRAINT fk_snapshot_template_item FOREIGN KEY (source_template_item_id) REFERENCES template_item (id),
    CONSTRAINT uk_snapshot_inspection_source_item UNIQUE (inspection_id, source_template_item_id),
    CONSTRAINT uk_snapshot_id_inspection UNIQUE (id, inspection_id)
);

CREATE INDEX ix_snapshot_inspection_id ON inspection_item_snapshot (inspection_id);
CREATE INDEX ix_snapshot_inspection_order ON inspection_item_snapshot (inspection_id, section_order, item_order);

CREATE TABLE inspection_response (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    inspection_item_id UUID NOT NULL,
    value_text TEXT,
    value_number DECIMAL(18,4),
    value_boolean BOOLEAN,
    value_date DATE,
    value_json TEXT,
    observation VARCHAR(2000),
    conformity VARCHAR(32),
    answered_at_device TIMESTAMP WITH TIME ZONE,
    server_received_at TIMESTAMP WITH TIME ZONE,
    answered_by UUID,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    version INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT fk_inspection_response_item FOREIGN KEY (inspection_item_id, inspection_id) REFERENCES inspection_item_snapshot (id, inspection_id) ON DELETE CASCADE,
    CONSTRAINT fk_inspection_response_answered_by FOREIGN KEY (answered_by) REFERENCES app_user (id),
    CONSTRAINT uk_inspection_response_item UNIQUE (inspection_item_id),
    CONSTRAINT uk_inspection_response_id_inspection UNIQUE (id, inspection_id),
    CONSTRAINT ck_inspection_response_conformity CHECK (conformity IS NULL OR conformity IN ('NOT_APPLICABLE', 'CONFORMING', 'NON_CONFORMING')),
    CONSTRAINT ck_inspection_response_version CHECK (version >= 0)
);

CREATE INDEX ix_inspection_response_inspection_id ON inspection_response (inspection_id);

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
