-- Staff can ask for an account from the login page. It stays unusable until an admin approves it.
ALTER TABLE app_user ADD COLUMN pending_approval BOOLEAN NOT NULL DEFAULT FALSE;
