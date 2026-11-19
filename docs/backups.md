# Backups

`ops/backup.py` backs up one AI Call CRM: its PostgreSQL database and its call recordings folder. It keeps each
backup for a set number of days and then deletes it. It can also prove that the newest backup actually restores.

It was run on 19 Nov 2026 against PostgreSQL 17, on a copy of a CRM database with 35 contacts and 36 AI calls:

- The database was dumped (99 kB, half a second) and checked.
- The dump was restored into a new database; every count matched the original.
- Restores into an existing name, or into the CRM's own database, were refused.
- With a wrong password or a wrong port, the run failed clearly and left no files behind.

## What you need

- **Python 3.10 or newer.** Nothing to install: the script uses only Python's own library.
- **The PostgreSQL client tools** (`pg_dump`, `pg_restore`, `psql`, `createdb`, `dropdb`).
  - Their version must be the database server's version or newer.
  - On Windows they come with the PostgreSQL installer and are found on their own.
  - Elsewhere, put them on the PATH or set `PG_BIN` to their folder.
- **A backup folder on another disk**: a second drive, a mounted network share, or a disk the hosting backs up.
  - A backup on the same disk as the database is lost with it.
- **For `check` only**, the CRM's database login must be allowed to create databases (PostgreSQL's CREATEDB right).
  - It is if the CRM's database user owns its server, as in a normal install.
  - On a hosted database without that right, run `check` on another computer with PostgreSQL:
    - create an empty database there with the CRM's database name;
    - point `DB_URL`, `DB_USERNAME` and `DB_PASSWORD` at that computer.

## Settings

The script reads the same settings as the CRM:

- It reads `backend/.env`, or another file given with `--env-file`.
- Environment variables override what is in that file.

| Setting | What it is |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | The CRM's own database (required) |
| `VOICE_RECORDINGS_DIR` | The recordings folder (see the note after this table) |
| `BACKUP_KEEP_DAYS` | Days backups are kept (default 14; `--keep-days` overrides it) |
| `PG_BIN` | Folder of the PostgreSQL tools, if they are not found on their own |

Notes:

- **A relative `VOICE_RECORDINGS_DIR`** is taken from the env file's folder. On a server, pass the full path with
  `--recordings`.
- **The password** goes to the PostgreSQL tools privately, in their environment. It is never put on a command line
  or printed.

## The three commands

```bash
python ops/backup.py backup  --to /mnt/backup/ai-call-crm
python ops/backup.py check   --to /mnt/backup/ai-call-crm
python ops/backup.py restore --to /mnt/backup/ai-call-crm --into ai_call_crm_restored
```

Each command prints one line of JSON saying what it did.

- **Success:** exit code 0.
- **Failure:** a line starting `BACKUP FAILED:` saying why, and exit code 1.
- **Missing settings:** exit code 2.

**`backup`** (daily) writes into the backup folder:

- **`db/<database>-YYYYMMDD-HHMMSS.dump`: the whole database.**
  - It is written under a temporary name first.
  - It gets its real name only once it is complete, can be read back, and holds the CRM's tables.
  - A failed run leaves nothing behind that could be mistaken for a backup.
- **`recordings/`: a copy of the recordings folder.** Each night copies only new and changed files.
  - If it finds no recordings folder, the run warns that the recordings were **not** backed up.
  - The JSON then says `"recordings_folder_found": false`. Fix the path.
- **`recordings-removed/YYYYMMDD/`: recordings the CRM has deleted since the last run.**
  - The CRM deletes recordings past their 365 days (`VOICE_RETENTION_DAYS`), and when a person is erased.
  - They are moved here, out of the copy, on the day the backup notices.
- **Deleting old backups.**
  - Dumps and removed recordings older than `BACKUP_KEEP_DAYS` are deleted.
  - The newest dump is always kept, however old it is. If backups stopped for a month, the last one is still there.

**`check`** (weekly) proves the newest dump restores:

- It restores the newest dump into a throwaway database, `<database>_restorecheck`.
- It counts migrations, staff, contacts, AI calls and audit entries.
- It drops the throwaway database again, even if the restore failed.
- It fails if the copy has no tables or no staff.

**`restore`** creates a **new** database from a backup:

- It uses the newest dump, or the one given with `--dump`.
- It never touches the CRM's own database or any database that already exists.

## Scheduling

Run `backup` once a night and `check` once a week, at a quiet time. Both are fine while the CRM is running:
`pg_dump` reads a consistent snapshot without stopping anyone.

**Linux (cron)**, as the user the CRM runs as:

```bash
30 2 * * *  cd /opt/ai-call-crm && python3 ops/backup.py backup --to /mnt/backup/ai-call-crm --recordings /opt/ai-call-crm/backend/data/recordings >> /var/log/ai-call-crm-backup.log 2>&1
0 4 * * 0   cd /opt/ai-call-crm && python3 ops/backup.py check --to /mnt/backup/ai-call-crm >> /var/log/ai-call-crm-backup.log 2>&1
```

**Windows (Task Scheduler)**, in a Command Prompt run as the CRM's user. Change the folders to yours.

```bash
schtasks /Create /SC DAILY /ST 02:30 /TN "AI Call CRM backup" /TR "cmd /c cd /d C:\ai-call-crm && python ops\backup.py backup --to E:\backups\ai-call-crm >> E:\backups\ai-call-crm.log 2>&1"
```

```bash
schtasks /Create /SC WEEKLY /D SUN /ST 04:00 /TN "AI Call CRM backup check" /TR "cmd /c cd /d C:\ai-call-crm && python ops\backup.py check --to E:\backups\ai-call-crm >> E:\backups\ai-call-crm.log 2>&1"
```

**Read the log every week.** A night that failed says `BACKUP FAILED` with the reason, and a missing recordings
folder says `WARNING`. A backup nobody looks at can fail for months unseen.

## How long backups are kept, and erasures

The default is 14 days (`BACKUP_KEEP_DAYS`). Decide the number with your adviser (compliance checklist, 2.1).

When a person is erased in the CRM:

- Their data is gone from the CRM at once.
- Their data stays in the backups made before the erasure, until those backups are deleted after `BACKUP_KEEP_DAYS`.
- Their recordings follow the same rule: they move to `recordings-removed/` at the next backup and are deleted that
  many days later.
- So with 14 days, every copy is gone within 16 days of the erasure.

## Restoring after a loss

1. **Pause all calling** (AI voice agent Overview, **Pause all**), if the CRM still runs.
2. **Choose the dump.**
   - The newest is in `db/`.
   - `python ops/backup.py check --to <folder>` shows what is in it without changing anything.
3. **Restore it into a new database.**
   - Run `python ops/backup.py restore --to <folder> --into ai_call_crm_restored`.
   - The JSON shows the counts. Compare them with what you expect.
4. **Point the CRM at the new database.**
   - Stop the CRM.
   - Change the database name at the end of `DB_URL` to `ai_call_crm_restored`.
   - Start the CRM.
   - Keep the old database until you are sure. It is never deleted for you.
5. **Bring back the recordings.**
   - Copy `recordings/` from the backup folder back into the CRM's recordings folder.
   - Do not copy `recordings-removed/`: those were deleted on purpose.
6. **Re-enter what happened after the dump, before calling resumes.** Anything after the dump's time
   (`YYYYMMDD-HHMMSS` in its name) is not in it. With nightly backups that is at most one day.
   - Most important: anyone who said **"stop calling me"**, was marked **Do not call**, or was **erased** in that
     time. Record those again, or the agent may call them again.
   - Staff can find them from their own notes, from Call-backs they handled, and from Plivo's call records for those
     hours.
7. **Unpause calling.**

**Before switching off the speech engine's computer for good**, check that its `/health` shows `"undelivered": 0`.
Call-backs and "stop" requests waiting in its outbox are not in any backup.
