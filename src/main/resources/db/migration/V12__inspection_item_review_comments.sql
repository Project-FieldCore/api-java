CREATE TABLE inspection_item_review_comment (
    id UUID PRIMARY KEY,
    inspection_id UUID NOT NULL,
    inspection_item_id UUID NOT NULL,
    reviewer_id UUID NOT NULL,
    comment VARCHAR(2000) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_item_review_comment_inspection FOREIGN KEY (inspection_id) REFERENCES inspection (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_review_comment_item FOREIGN KEY (inspection_item_id) REFERENCES inspection_item_snapshot (id) ON DELETE CASCADE,
    CONSTRAINT fk_item_review_comment_reviewer FOREIGN KEY (reviewer_id) REFERENCES app_user (id)
);

CREATE INDEX ix_item_review_comment_inspection_item ON inspection_item_review_comment (inspection_id, inspection_item_id, created_at, id);
