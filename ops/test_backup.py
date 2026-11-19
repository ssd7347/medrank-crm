"""Tests for ops/backup.py with a fake PostgreSQL (no server needed). The real run against PostgreSQL 17 is described
in docs/backups.md and training/STATUS.md (19 Nov 2026).

    python ops/test_backup.py        (from the repository folder)
"""

import io
import json
import sys
import tempfile
import unittest
from contextlib import redirect_stderr, redirect_stdout
from datetime import datetime
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import backup  # noqa: E402

SECRET = "pw-not-for-argv-123"
NOW = datetime(2026, 11, 19, 2, 30, 0)


class FakePg:
    """Plays pg_dump, pg_restore, psql, createdb and dropdb; records every command and its environment."""

    def __init__(self):
        self.calls, self.dbs = [], {"ai_call_crm"}
        self.fail = {}              # tool -> stderr to fail with
        self.toc = "; Archive\n3; 2615 TABLE public flyway_schema_history postgres\n"
        self.counts = "12|1|35|36|219"

    def __call__(self, cmd, env, capture_output, text):
        tool = Path(cmd[0]).stem
        self.calls.append((tool, cmd, env))
        res = type("R", (), {"returncode": 0, "stdout": "", "stderr": ""})()
        if tool in self.fail:
            res.returncode, res.stderr = 1, self.fail[tool]
            return res
        if tool == "pg_dump":
            Path(cmd[cmd.index("--file") + 1]).write_bytes(b"PGDMP fake dump")
        elif tool == "pg_restore" and "--list" in cmd:
            res.stdout = self.toc
        elif tool == "pg_restore":
            self.restored = cmd[cmd.index("-d") + 1]
            if "restore" in self.fail_after:
                res.returncode, res.stderr = 1, "pg_restore: error: boom"
        elif tool == "psql":
            sql = cmd[-1]
            if "pg_database" in sql:
                res.stdout = "1\n" if sql.split("'")[1] in self.dbs else "0\n"
            else:
                res.stdout = self.counts + "\n"
        elif tool == "createdb":
            self.dbs.add(cmd[-1])
        elif tool == "dropdb":
            self.dbs.discard(cmd[-1])
        return res

    fail_after: tuple = ()


class Base(unittest.TestCase):
    def setUp(self):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        self.dir = Path(d.name)
        self.env_file = self.dir / "backend" / ".env"
        self.env_file.parent.mkdir()
        self.env_file.write_text("# the CRM's settings\nDB_URL=jdbc:postgresql://db.local:5433/ai_call_crm\n"
                                 f"DB_USERNAME=crm\nDB_PASSWORD=\"{SECRET}\"\nVOICE_RECORDINGS_DIR=data/recordings\n",
                                 encoding="utf-8")
        self.rec = self.env_file.parent / "data" / "recordings"
        self.to = self.dir / "backups"
        self.pg = FakePg()

    def main(self, *args, environ=None, now=NOW):
        out, err = io.StringIO(), io.StringIO()
        with redirect_stdout(out), redirect_stderr(err):
            code = backup.main(list(args) + ["--env-file", str(self.env_file)], environ=environ or {"PG_BIN": "/pg"},
                               runner=self.pg, now=now)
        return code, out.getvalue(), err.getvalue()


class SettingsTest(Base):
    def test_jdbc_urls(self):
        self.assertEqual(backup.parse_jdbc("jdbc:postgresql://localhost/ai_call_crm"),
                         {"host": "localhost", "port": "5432", "dbname": "ai_call_crm"})
        self.assertEqual(backup.parse_jdbc("jdbc:postgresql://h:6543/crm?sslmode=require&x=1"),
                         {"host": "h", "port": "6543", "dbname": "crm", "sslmode": "require"})
        for bad in ("", "postgres://h/db", "jdbc:mysql://h/db", "jdbc:postgresql://h:5432/"):
            with self.assertRaises(backup.BackupError):
                backup.parse_jdbc(bad)

    def test_the_env_file_is_read_like_the_crm_and_the_environment_wins(self):
        s = backup.settings(self.env_file, {"DB_URL": "jdbc:postgresql://other/x", "UNRELATED": "y"})
        self.assertEqual((s["DB_URL"], s["DB_PASSWORD"], s["DB_USERNAME"]), ("jdbc:postgresql://other/x", SECRET, "crm"))
        self.assertNotIn("UNRELATED", s)

    def test_missing_settings_stop_before_anything_runs(self):
        self.env_file.write_text("DB_URL=jdbc:postgresql://h/db\n", encoding="utf-8")
        code, _, err = self.main("backup", "--to", str(self.to))
        self.assertEqual(code, 2)
        self.assertIn("DB_USERNAME, DB_PASSWORD", err)
        self.assertEqual(self.pg.calls, [])


class BackupTest(Base):
    def test_a_backup_dumps_checks_and_copies_recordings_and_the_password_is_never_on_a_command_line(self):
        (self.rec / "2026" / "11").mkdir(parents=True)
        (self.rec / "2026" / "11" / "a.ogg").write_bytes(b"one")
        code, out, err = self.main("backup", "--to", str(self.to))
        self.assertEqual((code, err), (0, ""))
        report = json.loads(out)
        self.assertEqual(report["dump"], "ai_call_crm-20261119-023000.dump")
        self.assertEqual(report["recordings"], {"copied": 1, "unchanged": 0, "moved_out": 0})
        self.assertTrue((self.to / "db" / report["dump"]).is_file())
        self.assertEqual((self.to / "recordings" / "2026" / "11" / "a.ogg").read_bytes(), b"one")
        tools = [t for t, _, _ in self.pg.calls]
        self.assertEqual(tools, ["pg_dump", "pg_restore"])
        for _, cmd, env in self.pg.calls:
            self.assertNotIn(SECRET, " ".join(cmd))
            self.assertEqual(env["PGPASSWORD"], SECRET)
            self.assertEqual(cmd[1:7], ["-h", "db.local", "-p", "5433", "-U", "crm"])
        self.assertNotIn(SECRET, out)

    def test_a_failed_dump_leaves_no_file_and_says_so(self):
        for tool, stderr in (("pg_dump", "connection refused"), ("pg_restore", "not a valid archive")):
            self.pg = FakePg()
            self.pg.fail = {tool: stderr}
            code, out, err = self.main("backup", "--to", str(self.to))
            self.assertEqual(code, 1)
            self.assertIn("BACKUP FAILED", err)
            self.assertIn(stderr, err)
            self.assertEqual(list((self.to / "db").iterdir()), [])

    def test_a_dump_without_the_crm_tables_is_not_kept(self):
        self.pg.toc = "; Archive\n3; 2615 TABLE public something_else postgres\n"
        code, _, err = self.main("backup", "--to", str(self.to))
        self.assertEqual(code, 1)
        self.assertIn("flyway_schema_history", err)
        self.assertEqual(list((self.to / "db").iterdir()), [])

    def test_recordings_the_crm_deleted_leave_the_copy_and_are_gone_after_keep_days(self):
        (self.rec).mkdir(parents=True)
        for name in ("a.ogg", "b.ogg"):
            (self.rec / name).write_bytes(name.encode())
        self.main("backup", "--to", str(self.to))
        (self.rec / "b.ogg").unlink()               # erased, or past its retention
        code, out, _ = self.main("backup", "--to", str(self.to), now=datetime(2026, 11, 20, 2, 30))
        self.assertEqual(json.loads(out)["recordings"], {"copied": 0, "unchanged": 1, "moved_out": 1})
        self.assertFalse((self.to / "recordings" / "b.ogg").exists())
        self.assertTrue((self.to / "recordings-removed" / "20261120" / "b.ogg").is_file())
        # Still there 14 days later; gone the day after.
        self.main("backup", "--to", str(self.to), now=datetime(2026, 12, 4, 2, 30))
        self.assertTrue((self.to / "recordings-removed" / "20261120" / "b.ogg").is_file())
        code, out, _ = self.main("backup", "--to", str(self.to), now=datetime(2026, 12, 5, 2, 30))
        self.assertEqual(json.loads(out)["removed_days_deleted"], 1)
        self.assertFalse((self.to / "recordings-removed" / "20261120").exists())

    def test_old_dumps_go_but_the_newest_always_stays(self):
        db = self.to / "db"
        db.mkdir(parents=True)
        for at in ("20261101-020000", "20261104-020000", "20261106-020000", "20261110-020000"):
            (db / f"ai_call_crm-{at}.dump").write_bytes(b"x")
        (db / "notes.txt").write_text("not a dump")
        counts = backup.prune(db, self.to / "recordings-removed", 14, NOW)
        self.assertEqual(counts["dumps_deleted"], 2)
        self.assertEqual(sorted(p.name for p in db.iterdir()),
                         ["ai_call_crm-20261106-020000.dump", "ai_call_crm-20261110-020000.dump", "notes.txt"])
        # Backups stopped for weeks: the last dump is kept however old it is.
        backup.prune(db, self.to / "recordings-removed", 14, datetime(2027, 3, 1))
        self.assertEqual(sorted(p.name for p in db.glob("*.dump")), ["ai_call_crm-20261110-020000.dump"])

    def test_a_missing_recordings_folder_is_warned_about_but_the_dump_is_kept(self):
        code, out, err = self.main("backup", "--to", str(self.to))
        self.assertEqual(code, 0)
        self.assertIn("recordings were NOT backed up", err)
        self.assertFalse(json.loads(out)["recordings_folder_found"])
        self.assertEqual(len(list((self.to / "db").glob("*.dump"))), 1)
        # --recordings points it at the right folder.
        elsewhere = self.dir / "srv" / "recs"
        elsewhere.mkdir(parents=True)
        (elsewhere / "c.ogg").write_bytes(b"c")
        code, out, err = self.main("backup", "--to", str(self.to), "--recordings", str(elsewhere))
        self.assertEqual((code, err), (0, ""))
        self.assertTrue(json.loads(out)["recordings_folder_found"])
        self.assertTrue((self.to / "recordings" / "c.ogg").is_file())

    def test_keep_days_must_be_sensible(self):
        code, _, err = self.main("backup", "--to", str(self.to), "--keep-days", "0")
        self.assertEqual(code, 1)
        self.assertIn("at least 1", err)


class RestoreTest(Base):
    def setUp(self):
        super().setUp()
        self.main("backup", "--to", str(self.to))
        self.pg.calls.clear()

    def test_restore_creates_a_new_database_only(self):
        code, out, _ = self.main("restore", "--to", str(self.to), "--into", "ai_call_crm_restored")
        self.assertEqual(code, 0)
        self.assertEqual(self.pg.restored, "ai_call_crm_restored")
        self.assertEqual(json.loads(out)["contacts"], 35)

    def test_restore_never_touches_an_existing_database_or_the_crms_own(self):
        self.pg.dbs.add("taken")
        for name, says in (("taken", "already exists"), ("ai_call_crm", "the CRM's own database"),
                           ("Bad-Name", "lower-case"), ("x;drop", "lower-case")):
            self.pg.calls.clear()
            code, _, err = self.main("restore", "--to", str(self.to), "--into", name)
            self.assertEqual(code, 1, name)
            self.assertIn(says, err)
            self.assertNotIn("createdb", [t for t, _, _ in self.pg.calls])
            self.assertNotIn("pg_restore", [t for t, _, _ in self.pg.calls])

    def test_the_check_restores_counts_and_always_drops_its_copy(self):
        code, out, _ = self.main("check", "--to", str(self.to))
        self.assertEqual(code, 0)
        self.assertEqual(json.loads(out)["ai_calls"], 36)
        self.assertNotIn("ai_call_crm_restorecheck", self.pg.dbs)
        self.assertEqual(self.pg.restored, "ai_call_crm_restorecheck")
        # A restore that fails half way: its copy is dropped all the same.
        self.pg.fail_after = ("restore",)
        code, _, err = self.main("check", "--to", str(self.to))
        self.assertEqual(code, 1)
        self.assertIn("boom", err)
        self.assertNotIn("ai_call_crm_restorecheck", self.pg.dbs)
        self.assertEqual(self.pg.calls[-1][0], "dropdb")

    def test_a_restored_copy_with_no_staff_or_schema_fails_the_check(self):
        self.pg.counts = "0|0|0|0|0"
        code, _, err = self.main("check", "--to", str(self.to))
        self.assertEqual(code, 1)
        self.assertIn("looks empty", err)

    def test_no_dump_no_check(self):
        for f in (self.to / "db").iterdir():
            f.unlink()
        code, _, err = self.main("check", "--to", str(self.to))
        self.assertEqual(code, 1)
        self.assertIn("no dump", err)


if __name__ == "__main__":
    unittest.main()
