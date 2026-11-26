-- Supports the administrative audit screen's default newest-first pagination.
CREATE INDEX IF NOT EXISTS ix_audit_event_created_at ON audit_event (created_at DESC, id DESC);
