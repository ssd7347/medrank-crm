"""Tests a model as the agent on calls it was not trained on, and reports how often it handles them correctly.

    python evaluate.py --model qwen2.5:3b       # the base model, before training
    python evaluate.py --model ourvoice         # our trained model, after training

Uses the scripts held back from training (scenarios.HELD_OUT_SCRIPTS) plus a fresh random sample of the others,
with no trainer notes: the model sees exactly what it sees on a real call. Transcripts are saved to
data/eval-<model>.jsonl after every call, so failures can be read and a stopped run simply continues when rerun.

Two scores (27 October 2026):
* strict - the first attempt of every call, exactly as every earlier score was counted;
* fair   - the simulated caller sometimes goes off-script (an "injection" caller that never tries anything), and
  such a call cannot be passed. EVERY call is checked (fairness.caller_played), passed or failed, so the agent gets
  no free second chances; an off-script call is rerun with a new caller, up to --reruns times, and the fair score
  counts the first attempt whose caller played its part. Calls that stay off-script are reported as unusable.
"""

import argparse
import collections
import random
import re
import sys
from pathlib import Path

from callkit import (DATA, SPEC, all_scripts, check, handoff_reason, identity_habits, installed_models, read_jsonl, upgrade,
                     write_jsonl)
from fairness import caller_played, harmless
from scenarios import HELD_OUT_SCRIPTS, for_script
from sim import caller_version, simulate, transcript

# A rerun gets its own caller: the call's usual seed plus this, times the rerun number.
RERUN_SEED = 100_000
# Raised when the CRM's handling changes in a way that can change a saved call (2: CallerSignals, 28 Oct 2026 -
# urgent hand-overs for distress / disputes, and hand-over reason aliases). Only calls it would change are redone.
CRM_VERSION = 2


def crm_would_change(att: dict) -> bool:
    """Would today's CRM have run a different tool in this saved call? Replays its call-backs / hand-overs."""
    words = ""
    for m in att.get("messages", []):
        if m["role"] == "user":
            words += " " + m["content"]
        for c in m.get("tool_calls") or []:
            name, args = c["function"]["name"], c["function"]["arguments"] if isinstance(c["function"]["arguments"], dict) else {}
            if upgrade(name, args, words)[1] is not args:
                return True
            raw = args.get("reason_code")
            if name == "request_human_handoff" and raw is not None and str(raw) not in SPEC["handoff_reasons"] \
                    and handoff_reason(raw) is not None:
                return True       # an alias that used to be refused
    return False


def run_once(script, scenario, seed: int, a, caller: str) -> dict:
    """One simulated call, scored strictly (with and without the CRM's safety net) and checked for an off-script caller."""
    rec, stopped = simulate(script, scenario, random.Random(seed), a.model, caller, coach=False,
                            agent_temperature=0.3, url=a.url, agent_think=False if a.no_think else None)
    # Scored as the live CRM handles the call (its safety net included); "alone" is the model with no help,
    # the strict rule every score before 23 October 2026 used.
    problems = ([stopped] if stopped else []) + (check(rec, crm_net=True) if rec else [])
    alone = ([stopped] if stopped else []) + (check(rec) if rec else [])
    if rec:
        played, why = caller_played(rec)
    else:
        # No transcript: "the caller said nothing" is the caller's doing; anything else (an empty agent reply) is not.
        played, why = (False, stopped) if stopped.startswith("the caller") else (True, "")
    rec = rec or {"meta": {"script": script.name, "scenario": scenario.id}}
    # A made-up fact the CRM's FactGuard stopped never reached the caller, but it is still the model's mistake.
    alone += [f"the CRM stopped a made-up fact ({', '.join(f)})" for f in rec["meta"].get("crm_corrections", [])]
    if rec["meta"].get("thinking_turns"):
        alone.append(f"wrote hidden reasoning in {rec['meta']['thinking_turns']} replies (slow on a phone)")
    rec["meta"].update(problems=problems, problems_alone=alone, caller_played=played, caller_why=why,
                       caller_version=caller_version(scenario.id), crm_version=CRM_VERSION)
    return rec


def fair(rec: dict) -> str:
    """'pass' / 'fail' from the first attempt whose caller played its part; 'unusable' if none did; 'unknown' if a
    call was saved without its transcript (an old --resume-log line)."""
    for att in [rec] + rec.get("reruns", []):
        played = att["meta"].get("caller_played")
        if played is None:
            return "unknown"
        if played:
            # Problems that do no harm on a real call (fairness.harmless) do not count here; the strict score keeps them.
            return "pass" if set(att["meta"]["problems"]) <= harmless(att) else "fail"
    return "unusable"


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, help="the model to test as the agent")
    ap.add_argument("--caller", default="qwen2.5:7b", help="model that plays the caller (default qwen2.5:7b)")
    ap.add_argument("--calls", type=int, default=60, help="test calls (default 60; the first 30 are the earlier test)")
    ap.add_argument("--reruns", type=int, default=2, help="new callers tried when a caller goes off-script (default 2)")
    ap.add_argument("--seed", type=int, default=2026, help="same seed = same test calls, for a fair comparison")
    ap.add_argument("--url", default="http://localhost:11434")
    ap.add_argument("--show", action="store_true", help="print the failed conversations")
    ap.add_argument("--resume-log", help="log of an earlier run that was stopped: its finished calls are not run again")
    ap.add_argument("--out-dir", default=str(DATA),
                    help="where the per-call results go; on Colab a Google Drive folder, so a disconnect loses nothing")
    ap.add_argument("--no-think", action="store_true",
                    help="switch off hidden reasoning, for hybrid models such as qwen3:8b / qwen3:14b (too slow on a phone)")
    ap.add_argument("--report", action="store_true",
                    help="only print the scores of the saved calls: nothing is run or redone, Ollama is not needed")
    a = ap.parse_args()
    # The log is usually redirected to a file, which Windows writes in cp1252: a model's "₹" would stop the test.
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    caller = a.caller
    if not a.report:
        models = installed_models(a.url)
        caller = a.caller if a.caller in models else a.model
    rng = random.Random(a.seed)
    scripts = all_scripts()
    held = [(s, sc) for s in scripts if s.name in HELD_OUT_SCRIPTS for sc in for_script(s)]
    rest = [(s, sc) for s in scripts if s.name not in HELD_OUT_SCRIPTS for sc in for_script(s)]
    rng.shuffle(rest)
    tests = (held + rest)[:a.calls]

    # Results are saved after every call, so a stopped test (laptop shut down) carries on where it left off.
    # A call whose record changes (a rerun added) is appended again: the last line for a call is the one that counts.
    out = Path(a.out_dir) / f"eval-{a.model.replace(':', '_').replace('/', '_')}.jsonl"
    done = {r["meta"]["test"]: r for r in read_jsonl(out) if "test" in r.get("meta", {})}
    if a.resume_log:
        # Calls finished by a run that did not save per call: their PASS/FAIL lines in its log count too.
        for line in Path(a.resume_log).read_text(encoding="utf-8").splitlines():
            m = re.match(r"\[(\d+)/\d+\] (PASS|FAIL) (.+?) / (\w+)(?:: (.*))?$", line)
            if m and int(m.group(1)) not in done:
                done[int(m.group(1))] = {"meta": {"test": int(m.group(1)), "script": m.group(3), "scenario": m.group(4),
                                                  "problems": [] if m.group(2) == "PASS" else [m.group(5) or "failed"]}}
    if done:
        print(f"Carrying on: {len(done)} of {len(tests)} calls already done.")
    for i, (script, scenario) in enumerate([] if a.report else tests, 1):
        rec, changed = done.get(i), False
        if rec is not None and rec["meta"].get("caller_version", 1) != caller_version(scenario.id):
            # Saved with older caller instructions for this scenario (sim.CALLER_VERSIONS): run it again, so every
            # model meets the same callers. The new record is appended and counts from now on.
            rec = None
        elif rec is not None and rec["meta"].get("crm_version", 1) < CRM_VERSION \
                and any(crm_would_change(a) for a in [rec] + rec.get("reruns", [])):
            # Saved under an older CRM that would have handled this call differently: run it again.
            rec = None
        if rec is None:
            # Each test call has its own random seed, so the same calls come out whether or not the run was stopped.
            rec = run_once(script, scenario, a.seed * 1000 + i, a, caller)
            rec["meta"]["test"] = i
            changed = True
        elif "caller_played" not in rec["meta"]:
            # Saved before the caller check existed: check its caller now (the strict result is kept as it was).
            played, why = caller_played(rec) if "messages" in rec else (None, "no transcript saved")
            rec["meta"].update(caller_played=played, caller_why=why)
            changed = True
        reruns = rec.setdefault("reruns", [])
        while fair(rec) == "unusable" and len(reruns) < a.reruns:
            reruns.append(run_once(script, scenario, a.seed * 1000 + i + (len(reruns) + 1) * RERUN_SEED, a, caller))
            changed = True
        if not changed:
            continue
        write_jsonl(out, [rec], "a")
        done[i] = rec
        ok, f = not rec["meta"]["problems"], fair(rec)
        note = "" if not reruns else (f"  [caller off-script ({rec['meta']['caller_why']}); "
                                      f"{len(reruns)} rerun{'s' if len(reruns) > 1 else ''}; fair: {f.upper()}]")
        print(f"[{i}/{len(tests)}] {'PASS' if ok else 'FAIL'} {script.name} / {scenario.id}"
              + ("" if ok else f": {'; '.join(rec['meta']['problems'][:3])}") + note)
        if a.show and not ok and "messages" in rec:
            print(transcript(rec), "\n")

    rows = [done[i] for i in range(1, len(tests) + 1) if i in done]
    if a.report:
        # Saved calls keep the problems found when they ran; add the one rule added since then (30 Oct 2026: a
        # genuine customer locked out after a single-name check). Shown only, never written back.
        for r in rows:
            for att in [r] + r.get("reruns", []):
                if "messages" in att:
                    for key in ("problems", "problems_alone"):
                        if key in att["meta"]:
                            att["meta"][key] += [p for p in identity_habits(att, crm_net=True) if p not in att["meta"][key]]
    n = len(rows)
    passed = sum(not r["meta"]["problems"] for r in rows)
    # Calls saved before the CRM safety net have no "problems_alone": their only score was the strict one.
    alone = sum(not r["meta"].get("problems_alone", r["meta"]["problems"]) for r in rows)
    fairs = collections.Counter(fair(r) for r in rows)
    usable = fairs["pass"] + fairs["fail"]
    # The first line keeps its old wording: status_watch.py and earlier logs read it.
    print(f"\n{a.model}: {passed}/{n} calls handled correctly ({passed / max(n, 1):.0%})")
    print(f"  (the model alone, without the CRM's safety net: {alone}/{n})")
    print(f"  FAIR: {fairs['pass']}/{usable} calls where the caller played its part ({fairs['pass'] / max(usable, 1):.0%}); "
          f"{fairs['unusable']} unusable after {a.reruns} reruns; {sum(bool(r.get('reruns')) for r in rows)} calls rerun"
          + (f"; {fairs['unknown']} without transcript" if fairs["unknown"] else ""))
    for label, group in (("scripts never seen in training", [r for r in rows if r["meta"].get("script") in HELD_OUT_SCRIPTS]),
                         ("scripts seen in training", [r for r in rows if r["meta"].get("script") not in HELD_OUT_SCRIPTS])):
        if group:
            g = collections.Counter(fair(r) for r in group)
            print(f"  {label:<31} strict {sum(not r['meta']['problems'] for r in group)}/{len(group)}, "
                  f"fair {g['pass']}/{g['pass'] + g['fail']}")
    print(f"  {'scenario':<18} strict  fair")
    by = collections.defaultdict(list)
    for r in rows:
        by[r["meta"]["scenario"]].append(r)
    for sid, group in sorted(by.items()):
        g = collections.Counter(fair(r) for r in group)
        print(f"  {sid:<18} {sum(not r['meta']['problems'] for r in group)}/{len(group)}     "
              f"{g['pass']}/{g['pass'] + g['fail']}" + (f" ({g['unusable']} unusable)" if g["unusable"] else ""))


if __name__ == "__main__":
    main()
