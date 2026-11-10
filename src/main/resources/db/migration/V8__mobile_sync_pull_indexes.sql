-- PBI-0053.2: pull-by-cursor support.
-- Additive only. No rewrite of existing rows.

-- 1) Inspection pull: technician watermark scans
CREATE INDEX ix_inspection_technician_updated_at
    ON inspection (technician_id, updated_at, id);

-- 2) Response pull: time-ordered scan then join-filter by inspection.technician_id.
CREATE INDEX ix_inspection_response_updated_at
    ON inspection_response (updated_at, id);

-- 3) Minimal tombstones for reassignment-away DELETE (not a full changelog).
CREATE TABLE mobile_sync_tombstone (
    id UUID PRIMARY KEY,
    technician_id UUID NOT NULL,
    entity_type VARCHAR(64) NOT NULL,
    entity_id UUID NOT NULL,
    removed_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_mobile_sync_tombstone_technician
        FOREIGN KEY (technician_id) REFERENCES app_user (id),
    CONSTRAINT ck_mobile_sync_tombstone_entity_type
        CHECK (entity_type IN ('INSPECTION'))
);

CREATE INDEX ix_mobile_sync_tombstone_technician_removed
    ON mobile_sync_tombstone (technician_id, removed_at, id);

CREATE INDEX ix_mobile_sync_tombstone_entity
    ON mobile_sync_tombstone (entity_type, entity_id);
