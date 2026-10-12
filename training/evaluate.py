"""Tests a model as the agent on calls it was not trained on, and reports how often it handles them correctly.

    python evaluate.py --model qwen2.5:3b       # the base model, before training
    python evaluate.py --model ourvoice         # our trained model, after training

Uses the scripts held back from training (scenarios.HELD_OUT_SCRIPTS) plus a fresh random sample of the others,
with no trainer notes: the model sees exactly what it sees on a real call. Transcripts are saved to
data/eval-<model>.jsonl after every call, so failures can be read and a stopped run simply continues when rerun.
"""

import argparse
import collections
import random
import re
from pathlib import Path

from callkit import DATA, all_scripts, check, installed_models, read_jsonl, write_jsonl
from scenarios import HELD_OUT_SCRIPTS, for_script
from sim import simulate, transcript


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True, help="the model to test as the agent")
    ap.add_argument("--caller", default="qwen2.5:7b", help="model that plays the caller (default qwen2.5:7b)")
    ap.add_argument("--calls", type=int, default=30)
    ap.add_argument("--seed", type=int, default=2026, help="same seed = same test calls, for a fair comparison")
    ap.add_argument("--url", default="http://localhost:11434")
    ap.add_argument("--show", action="store_true", help="print the failed conversations")
    ap.add_argument("--resume-log", help="log of an earlier run that was stopped: its finished calls are not run again")
    a = ap.parse_args()

    models = installed_models(a.url)
    caller = a.caller if a.caller in models else a.model
    rng = random.Random(a.seed)
    scripts = all_scripts()
    held = [(s, sc) for s in scripts if s.name in HELD_OUT_SCRIPTS for sc in for_script(s)]
    rest = [(s, sc) for s in scripts if s.name not in HELD_OUT_SCRIPTS for sc in for_script(s)]
    rng.shuffle(rest)
    tests = (held + rest)[:a.calls]

    # Results are saved after every call, so a stopped test (laptop shut down) carries on where it left off.
    out = DATA / f"eval-{a.model.replace(':', '_').replace('/', '_')}.jsonl"
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
    for i, (script, scenario) in enumerate(tests, 1):
        if i in done:
            continue
        # Each test call has its own random seed, so the same calls come out whether or not the run was stopped.
        rec, stopped = simulate(script, scenario, random.Random(a.seed * 1000 + i), a.model, caller, coach=False,
                                agent_temperature=0.3, url=a.url)
        problems = ([stopped] if stopped else []) + (check(rec) if rec else [])
        rec = rec or {"meta": {"script": script.name, "scenario": scenario.id}}
        rec["meta"].update(test=i, problems=problems)
        write_jsonl(out, [rec], "a")
        done[i] = rec
        ok = not problems
        print(f"[{i}/{len(tests)}] {'PASS' if ok else 'FAIL'} {script.name} / {scenario.id}" + ("" if ok else f": {'; '.join(problems[:3])}"))
        if a.show and not ok and "messages" in rec:
            print(transcript(rec), "\n")

    by_scenario = collections.defaultdict(lambda: [0, 0])
    for r in done.values():
        by_scenario[r["meta"]["scenario"]][0] += not r["meta"]["problems"]
        by_scenario[r["meta"]["scenario"]][1] += 1
    passed = sum(ok for ok, _ in by_scenario.values())
    print(f"\n{a.model}: {passed}/{len(done)} calls handled correctly ({passed / max(len(done), 1):.0%})")
    for sid, (ok, n) in sorted(by_scenario.items()):
        print(f"  {sid:<18} {ok}/{n}")


if __name__ == "__main__":
    main()
