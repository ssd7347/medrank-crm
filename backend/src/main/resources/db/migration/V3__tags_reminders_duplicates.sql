-- Everyday CRM features: tags, follow-up reminders, and "these two are not the same person".

-- Free-form labels on contacts ("Hot lead", "Chennai", "VIP"). Spelling is kept as first typed; matching
-- ignores upper/lower case.
CREATE TABLE contact_tag (
    contact_id  BIGINT      NOT NULL REFERENCES contact (id),
    tag         VARCHAR(40) NOT NULL,
    PRIMARY KEY (contact_id, tag)
);
CREATE INDEX ix_contact_tag_tag ON contact_tag (tag);

-- Campaigns can call everyone with one tag.
ALTER TABLE voice_campaign ADD COLUMN filter_tag VARCHAR(40);

-- When the assignee was reminded that a follow-up is due, so each one is reminded once.
ALTER TABLE follow_up ADD COLUMN reminded_at TIMESTAMP WITH TIME ZONE;

-- Pairs someone has checked and said are different people, so the duplicate finder stops suggesting them.
-- contact_a is always the smaller id.
CREATE TABLE duplicate_dismissal (
    contact_a     BIGINT NOT NULL REFERENCES contact (id),
    contact_b     BIGINT NOT NULL REFERENCES contact (id),
    dismissed_by  BIGINT REFERENCES app_user (id),
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (contact_a, contact_b)
);
