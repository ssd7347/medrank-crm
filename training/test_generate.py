"""Tests for generate.py with a fake simulator and checker: where calls go, focus, the per-scenario cap, carrying on.

    python -m unittest test_generate
"""

import collections
import contextlib
import io
import sys
import tempfile
import unittest
from pathlib import Path

import generate
from callkit import read_jsonl
from scenarios import HELD_OUT_SCRIPTS, for_script


class GenerateTest(unittest.TestCase):

    def run_gen(self, out_dir, *args, bad=()):
        """Runs generate.main(); every simulated call passes the checker unless its scenario is in ``bad``."""
        tried, kw_seen = [], []

        def fake_simulate(script, scenario, rng, teacher, caller, coach, **kw):
            tried.append((script.name, scenario.id))
            kw_seen.append((teacher, caller, coach, kw))
            return {"messages": [], "meta": {"script": script.name, "scenario": scenario.id}}, ""
        real = (generate.simulate, generate.check, generate.installed_models, sys.argv)
        generate.simulate = fake_simulate
        generate.check = lambda rec: ["bad call"] if rec["meta"]["scenario"] in bad else []
        generate.installed_models = lambda url: ["ourvoice8b", "qwen2.5:7b"]
        sys.argv = ["generate.py", "--teacher", "ourvoice8b", "--caller", "qwen2.5:7b", "--seed", "1",
                    "--out", "gen.jsonl", "--out-dir", out_dir, *args]
        try:
            printed = io.StringIO()
            with contextlib.redirect_stdout(printed):
                generate.main()
        finally:
            generate.simulate, generate.check, generate.installed_models, sys.argv = real
        return tried, kw_seen, printed.getvalue()

    def test_calls_go_to_the_drive_folder_and_a_rerun_carries_on(self):
        with tempfile.TemporaryDirectory() as d:
            tried, kw, _ = self.run_gen(d, "--count", "5", "--no-think", bad={"dispute"})
            kept = read_jsonl(Path(d) / "gen.jsonl")
            rejected = read_jsonl(Path(d) / "rejected.jsonl")
            self.assertEqual(len(kept), 5)
            self.assertEqual(len(kept) + len(rejected), len(tried))
            self.assertTrue(all(r["meta"]["scenario"] == "dispute" for r in rejected))
            self.assertTrue(all(r["meta"]["problems"] == ["bad call"] for r in rejected))
            # The test's own settings: the trained 8B with the real caller, trainer notes on, no hidden reasoning.
            self.assertEqual({(t, c, coach, k["agent_think"]) for t, c, coach, k in kw}, {("ourvoice8b", "qwen2.5:7b", True, False)})
            # Rerun with a higher target: the 5 already kept count.
            tried2, _, out = self.run_gen(d, "--count", "7")
            self.assertIn("5 good conversations so far", out)
            self.assertEqual(len(read_jsonl(Path(d) / "gen.jsonl")), 7)
            self.assertEqual(len(tried2), 2)

    def test_never_the_test_scripts(self):
        with tempfile.TemporaryDirectory() as d:
            tried, _, _ = self.run_gen(d, "--count", "300")
            self.assertFalse({s for s, _ in tried} & HELD_OUT_SCRIPTS)

    def test_focus_scenarios_are_tried_three_times_as_often(self):
        pairs = [(s, sc) for s in generate.all_scripts() if s.name not in HELD_OUT_SCRIPTS for sc in for_script(s)]
        per_pass = collections.Counter(sc.id for _, sc in pairs)
        with tempfile.TemporaryDirectory() as d:
            # Every call is kept, so a target of exactly one pass over the focused list gives exact counts.
            focused, _, _ = self.run_gen(d, "--count", str(len(pairs) + 2 * per_pass["dispute"]), "--focus", "dispute")
        got = collections.Counter(sc for _, sc in focused)
        self.assertGreater(per_pass["dispute"], 0)
        self.assertEqual(got["dispute"], 3 * per_pass["dispute"])
        self.assertEqual(got["busy"], per_pass["busy"])

    def test_the_per_scenario_cap_and_the_stop_when_all_are_full(self):
        with tempfile.TemporaryDirectory() as d:
            tried, _, out = self.run_gen(d, "--count", "100000", "--max-per-scenario", "2")
            kept = collections.Counter(r["meta"]["scenario"] for r in read_jsonl(Path(d) / "gen.jsonl"))
            self.assertEqual(set(kept.values()), {2})
            self.assertIn("Every scenario has 2 kept calls", out)

    def test_an_unknown_focus_scenario_stops_at_once(self):
        with tempfile.TemporaryDirectory() as d, self.assertRaises(SystemExit) as e:
            self.run_gen(d, "--focus", "dispute,disputes")
        self.assertIn("disputes", str(e.exception))


if __name__ == "__main__":
    unittest.main()
