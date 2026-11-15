-- On our own speech engine a transfer to the desk waits until the caller has heard "I am connecting you".
ALTER TABLE voice_call ADD COLUMN transfer_pending BOOLEAN NOT NULL DEFAULT FALSE;
