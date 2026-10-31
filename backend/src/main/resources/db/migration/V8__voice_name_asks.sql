-- How many identity checks in this call had only part of the name, so the agent cannot ask for it for ever.
ALTER TABLE voice_call ADD COLUMN name_asks INT NOT NULL DEFAULT 0;
