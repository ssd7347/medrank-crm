"""
Backups of one AI Call CRM: its PostgreSQL database and its call recordings folder.

    python ops/backup.py backup  --to <backup folder>     the daily run (schedule it: docs/backups.md)
    python ops/backup.py check   --to <backup folder>     restores the newest dump into a throwaway database, checks it, drops it
    python ops/backup.py restore --to <backup folder> --into <new database name> [--dump <file>]

What ``backup`` does, in the backup folder:
  db/<database>-YYYYMMDD-HHMMSS.dump   pg_dump (custom format), written as .partial and renamed only when complete and
                                       readable (pg_restore --list)
  recordings/                          a copy of the recordings folder: new and changed files are copied
  recordings-removed/YYYYMMDD/         recordings the CRM has deleted since (its retention, or an erasure)
Dumps and removed recordings older than --keep-days (default 14) are deleted, so an erased person's data is gone from
the backups too after that many days. The newest dump is never deleted, whatever its age.

Settings come from the CRM's own settings file (--env-file, default backend/.env) and environment variables, which win:
DB_URL (jdbc:postgresql://host:port/database), DB_USERNAME, DB_PASSWORD, VOICE_RECORDINGS_DIR (relative paths are
from the env file's folder, where the CRM runs), BACKUP_KEEP_DAYS, PG_BIN (folder of pg_dump and the others, when
they are not on the PATH). The password goes to the PostgreSQL tools in their environment (PGPASSWORD), never on a
command line or in the output.

Nothing here writes to the CRM's own database. ``restore`` only ever creates a NEW database (it refuses a name that
exists); switching the CRM to it is a separate, deliberate step (docs/backups.md).
"""

from __future__ import annotations

import argparse
import glob
import json
import os
import re
import shutil
import subprocess
import sys
import time
from datetime import datetime, timedelta
from pathlib import Path

HERE = Path(__file__).resolve().parent
DEFAULT_ENV_FILE = HERE.parent / "backend" / ".env"
DUMP_NAME = re.compile(r"^(?P<db>[A-Za-z0-9_]+)-(?P<at>\d{8}-\d{6})\.dump$")
DAY = re.compile(r"^\d{8}$")
SAFE_DB = re.compile(r"^[a-z_][a-z0-9_]{0,62}$")


class BackupError(Exception):
    pass


# ---------------------------------------------------------------------------------------------- settings


def read_env_file(path: Path) -> dict[str, str]:
    """KEY=VALUE lines, as the CRM reads backend/.env: comments and blank lines skipped, surrounding quotes removed."""
    out: dict[str, str] = {}
    if not path.is_file():
        return out
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, value = line.split("=", 1)
        value = value.strip()
        if len(value) >= 2 and value[0] == value[-1] and value[0] in "\"'":
            value = value[1:-1]
        out[key.strip()] = value
    return out


def settings(env_file: Path, environ: dict[str, str]) -> dict[str, str]:
    merged = read_env_file(env_file)
    merged.update({k: v for k, v in environ.items() if k in (
        "DB_URL", "DB_USERNAME", "DB_PASSWORD", "VOICE_RECORDINGS_DIR", "BACKUP_KEEP_DAYS", "PG_BIN")})
    return merged


def parse_jdbc(url: str) -> dict[str, str]:
    """jdbc:postgresql://host[:port]/database[?params] -> host, port, dbname, sslmode (if given)."""
    m = re.fullmatch(r"jdbc:postgresql://([^/:?]+)(?::(\d+))?/([^?/]+)(?:\?(.*))?", (url or "").strip())
    if not m:
        raise BackupError("DB_URL must look like jdbc:postgresql://host:5432/database")
    out = {"host": m[1], "port": m[2] or "5432", "dbname": m[3]}
    for part in (m[4] or "").split("&"):
        if part.startswith("sslmode="):
            out["sslmode"] = part.split("=", 1)[1]
    return out


# ---------------------------------------------------------------------------------------------- PostgreSQL tools


def find_bin(pg_bin: str | None) -> Path | None:
    """PG_BIN, else the PATH (None), else on Windows the newest C:\\Program Files\\PostgreSQL\\<n>\\bin."""
    if pg_bin:
        return Path(pg_bin)
    if shutil.which("pg_dump"):
        return None
    found = sorted(glob.glob(r"C:\Program Files\PostgreSQL\*\bin\pg_dump.exe"),
                   key=lambda p: int(re.search(r"PostgreSQL\\(\d+)", p)[1]) if re.search(r"PostgreSQL\\(\d+)", p) else 0)
    return Path(found[-1]).parent if found else None


class Pg:
    """Runs pg_dump, pg_restore, psql, createdb and dropdb against the CRM's server with its login."""

    def __init__(self, conn: dict[str, str], user: str, password: str, bin_dir: Path | None, runner=subprocess.run):
        self.conn, self.user, self.bin_dir, self.runner = conn, user, bin_dir, runner
        self.env = dict(os.environ, PGPASSWORD=password, PGCONNECT_TIMEOUT="10")
        if conn.get("sslmode"):
            self.env["PGSSLMODE"] = conn["sslmode"]

    def tool(self, name: str) -> str:
        return str(self.bin_dir / name) if self.bin_dir else name

    def run(self, name: str, args: list[str]) -> str:
        cmd = [self.tool(name), "-h", self.conn["host"], "-p", self.conn["port"], "-U", self.user] + args
        try:
            r = self.runner(cmd, env=self.env, capture_output=True, text=True)
        except FileNotFoundError:
            raise BackupError(f"{name} was not found: install the PostgreSQL client tools or set PG_BIN") from None
        if r.returncode != 0:
            raise BackupError(f"{name} failed: {(r.stderr or r.stdout or '').strip()[:500]}")
        return r.stdout

    def exists(self, db: str) -> bool:
        out = self.run("psql", ["-d", self.conn["dbname"], "-At", "-v", "ON_ERROR_STOP=1", "-c",
                                f"select count(*) from pg_database where datname = '{db}'"])
        return out.strip() == "1"


# ---------------------------------------------------------------------------------------------- backup


def stamp(now: datetime) -> str:
    return now.strftime("%Y%m%d-%H%M%S")


def dump(pg: Pg, db_dir: Path, now: datetime) -> Path:
    db_dir.mkdir(parents=True, exist_ok=True)
    final = db_dir / f"{pg.conn['dbname']}-{stamp(now)}.dump"
    partial = final.with_name(final.name + ".partial")
    try:
        pg.run("pg_dump", ["-d", pg.conn["dbname"], "--format=custom", "--no-owner", "--no-privileges",
                           "--file", str(partial)])
        if not partial.is_file() or partial.stat().st_size == 0:
            raise BackupError("pg_dump wrote nothing")
        listing = pg.run("pg_restore", ["--list", str(partial)])      # the dump can be read back
        if "flyway_schema_history" not in listing:
            raise BackupError("the dump does not hold the CRM's tables (no flyway_schema_history)")
        os.replace(partial, final)
    finally:
        partial.unlink(missing_ok=True)
    return final


def mirror_recordings(source: Path, mirror: Path, removed_root: Path, today: str) -> dict[str, int]:
    """Copies new and changed recordings into ``mirror``; moves copies whose original the CRM deleted into
    ``removed_root/today`` (kept until they are older than --keep-days, see prune)."""
    counts = {"copied": 0, "unchanged": 0, "moved_out": 0}
    if not source.is_dir():
        return counts
    mirror.mkdir(parents=True, exist_ok=True)
    seen = set()
    for src in source.rglob("*"):
        if not src.is_file():
            continue
        rel = src.relative_to(source)
        seen.add(rel)
        dst = mirror / rel
        s = src.stat()
        if dst.is_file() and dst.stat().st_size == s.st_size and int(dst.stat().st_mtime) == int(s.st_mtime):
            counts["unchanged"] += 1
            continue
        dst.parent.mkdir(parents=True, exist_ok=True)
        tmp = dst.with_name(dst.name + ".partial")
        shutil.copy2(src, tmp)
        os.replace(tmp, dst)
        counts["copied"] += 1
    for dst in sorted(mirror.rglob("*")):
        if dst.is_file() and dst.relative_to(mirror) not in seen:
            target = removed_root / today / dst.relative_to(mirror)
            target.parent.mkdir(parents=True, exist_ok=True)
            os.replace(dst, target)
            counts["moved_out"] += 1
    return counts


def prune(db_dir: Path, removed_root: Path, keep_days: int, now: datetime) -> dict[str, int]:
    """Deletes dumps and removed recordings older than keep_days, by the date in their names. The newest dump stays."""
    cutoff = now - timedelta(days=keep_days)
    counts = {"dumps_deleted": 0, "removed_days_deleted": 0}
    dumps = sorted((p for p in db_dir.glob("*.dump") if DUMP_NAME.match(p.name)),
                   key=lambda p: DUMP_NAME.match(p.name)["at"])
    for p in dumps[:-1]:
        if datetime.strptime(DUMP_NAME.match(p.name)["at"], "%Y%m%d-%H%M%S") < cutoff:
            p.unlink()
            counts["dumps_deleted"] += 1
    if removed_root.is_dir():
        for d in removed_root.iterdir():
            if d.is_dir() and DAY.match(d.name) and datetime.strptime(d.name, "%Y%m%d") < cutoff - timedelta(days=1):
                shutil.rmtree(d)
                counts["removed_days_deleted"] += 1
    return counts


def newest_dump(db_dir: Path) -> Path:
    dumps = sorted((p for p in db_dir.glob("*.dump") if DUMP_NAME.match(p.name)),
                   key=lambda p: DUMP_NAME.match(p.name)["at"])
    if not dumps:
        raise BackupError(f"no dump in {db_dir}")
    return dumps[-1]


# ---------------------------------------------------------------------------------------------- restore


def restore_into(pg: Pg, dump_file: Path, target: str) -> None:
    """Creates ``target`` (which must not exist) and restores the dump into it."""
    if not SAFE_DB.match(target):
        raise BackupError("the new database name must be lower-case letters, digits and _")
    if target == pg.conn["dbname"]:
        raise BackupError(f"{target} is the CRM's own database: restore into a NEW name, then switch DB_URL")
    if pg.exists(target):
        raise BackupError(f"database {target} already exists: choose a new name (nothing is ever overwritten)")
    pg.run("createdb", ["--maintenance-db", pg.conn["dbname"], "-T", "template0", target])
    pg.run("pg_restore", ["-d", target, "--no-owner", "--no-privileges", "--exit-on-error", str(dump_file)])


def counts(pg: Pg, db: str) -> dict[str, int]:
    out = pg.run("psql", ["-d", db, "-At", "-v", "ON_ERROR_STOP=1", "-c",
                          "select (select count(*) from flyway_schema_history where success),"
                          " (select count(*) from app_user), (select count(*) from contact),"
                          " (select count(*) from voice_call), (select count(*) from audit_log)"])
    v = out.strip().split("|")
    return {"migrations": int(v[0] or 0), "staff_users": int(v[1]), "contacts": int(v[2]),
            "ai_calls": int(v[3]), "audit_entries": int(v[4])}


def check(pg: Pg, dump_file: Path) -> dict[str, int]:
    """Restores the dump into <database>_restorecheck, counts what is in it, and drops it again (also on failure)."""
    target = f"{pg.conn['dbname']}_restorecheck"[:63]
    pg.run("dropdb", ["--maintenance-db", pg.conn["dbname"], "--if-exists", "--force", target])
    try:
        restore_into(pg, dump_file, target)
        found = counts(pg, target)
    finally:
        pg.run("dropdb", ["--maintenance-db", pg.conn["dbname"], "--if-exists", "--force", target])
    if found["migrations"] < 1 or found["staff_users"] < 1:
        raise BackupError(f"the restored copy looks empty: {found}")
    return found


# ---------------------------------------------------------------------------------------------- command line


def main(argv: list[str] | None = None, environ: dict[str, str] | None = None, runner=subprocess.run,
         now: datetime | None = None) -> int:
    ap = argparse.ArgumentParser(description="Backups of the AI Call CRM (docs/backups.md)")
    ap.add_argument("command", choices=["backup", "check", "restore"])
    ap.add_argument("--to", required=True, help="the backup folder (ideally another disk or a mounted backup drive)")
    ap.add_argument("--env-file", default=str(DEFAULT_ENV_FILE))
    ap.add_argument("--keep-days", type=int)
    ap.add_argument("--recordings", help="the CRM's recordings folder (default: VOICE_RECORDINGS_DIR)")
    ap.add_argument("--into", help="restore: the NEW database to create")
    ap.add_argument("--dump", help="restore: which dump (default: the newest)")
    args = ap.parse_args(argv)
    environ = dict(os.environ) if environ is None else environ
    now = now or datetime.now()

    env_file = Path(args.env_file)
    s = settings(env_file, environ)
    missing = [k for k in ("DB_URL", "DB_USERNAME", "DB_PASSWORD") if not s.get(k)]
    if missing:
        print(f"Missing settings: {', '.join(missing)} (in {env_file} or the environment)", file=sys.stderr)
        return 2
    try:
        keep_days = args.keep_days if args.keep_days is not None else int(s.get("BACKUP_KEEP_DAYS") or 14)
        if keep_days < 1:
            raise BackupError("--keep-days must be at least 1")
        pg = Pg(parse_jdbc(s["DB_URL"]), s["DB_USERNAME"], s["DB_PASSWORD"], find_bin(s.get("PG_BIN")), runner)
        root = Path(args.to)
        db_dir, removed_root = root / "db", root / "recordings-removed"

        if args.command == "backup":
            t0 = time.monotonic()
            f = dump(pg, db_dir, now)
            rec = Path(args.recordings or s.get("VOICE_RECORDINGS_DIR") or "data/recordings")
            if not rec.is_absolute():
                rec = env_file.parent / rec
            found_rec = rec.is_dir()
            if not found_rec:
                # Not fatal (a new CRM has recorded nothing yet), but a wrong path must not pass unnoticed.
                print(f"WARNING: no recordings folder at {rec}: recordings were NOT backed up. Set "
                      "VOICE_RECORDINGS_DIR or --recordings to the CRM's folder.", file=sys.stderr)
            mirrored = mirror_recordings(rec, root / "recordings", removed_root, now.strftime("%Y%m%d"))
            pruned = prune(db_dir, removed_root, keep_days, now)
            print(json.dumps({"ok": True, "dump": f.name, "dump_kB": f.stat().st_size // 1024,
                              "recordings_folder": str(rec), "recordings_folder_found": found_rec,
                              "recordings": mirrored, **pruned,
                              "keep_days": keep_days, "seconds": round(time.monotonic() - t0, 1)}))
        elif args.command == "check":
            f = newest_dump(db_dir)
            found = check(pg, f)
            print(json.dumps({"ok": True, "dump": f.name, "restored_and_dropped": True, **found}))
        else:
            if not args.into:
                raise BackupError("restore needs --into <new database name>")
            f = Path(args.dump) if args.dump else newest_dump(db_dir)
            restore_into(pg, f, args.into)
            found = counts(pg, args.into)
            print(json.dumps({"ok": True, "dump": f.name, "restored_into": args.into, **found}))
            print(f"Restored. To run the CRM on it: stop the CRM, set DB_URL to .../{args.into}, start it.",
                  file=sys.stderr)
        return 0
    except BackupError as e:
        print(f"BACKUP FAILED: {e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
