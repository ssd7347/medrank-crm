"""Tests a model as the agent on calls it was not trained on, and reports how often it handles them correctly.

    python evaluate.py --model qwen2.5:3b       # the base model, before training
    python evaluate.py --model ourvoice         # our trained model, after training

Uses the scripts held back from training (scenarios.HELD_OUT_SCRIPTS) plus a fresh random sample of the others,
with no trainer notes: the model sees exactly what it sees on a real call. Transcripts are saved to
data/eval-<model>.jsonl so failures can be read.
"""

import argparse
import collections
import random

from callkit import DATA, all_scripts, check, installed_models, write_jsonl
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
    a = ap.parse_args()

    models = installed_models(a.url)
    caller = a.caller if a.caller in models else a.model
    rng = random.Random(a.seed)
    scripts = all_scripts()
    held = [(s, sc) for s in scripts if s.name in HELD_OUT_SCRIPTS for sc in for_script(s)]
    rest = [(s, sc) for s in scripts if s.name not in HELD_OUT_SCRIPTS for sc in for_script(s)]
    rng.shuffle(rest)
    tests = (held + rest)[:a.calls]

    passed, by_scenario, rows = 0, collections.defaultdict(lambda: [0, 0]), []
    for i, (script, scenario) in enumerate(tests, 1):
        rec, stopped = simulate(script, scenario, rng, a.model, caller, coach=False, agent_temperature=0.3, url=a.url)
        problems = ([stopped] if stopped else []) + (check(rec) if rec else [])
        ok = not problems
        passed += ok
        by_scenario[scenario.id][0] += ok
        by_scenario[scenario.id][1] += 1
        print(f"[{i}/{len(tests)}] {'PASS' if ok else 'FAIL'} {script.name} / {scenario.id}" + ("" if ok else f": {'; '.join(problems[:3])}"))
        if rec:
            rec["meta"]["problems"] = problems
            rows.append(rec)
            if a.show and not ok:
                print(transcript(rec), "\n")
    write_jsonl(DATA / f"eval-{a.model.replace(':', '_').replace('/', '_')}.jsonl", rows)
    print(f"\n{a.model}: {passed}/{len(tests)} calls handled correctly ({passed / max(len(tests), 1):.0%})")
    for sid, (ok, n) in sorted(by_scenario.items()):
        print(f"  {sid:<18} {ok}/{n}")


if __name__ == "__main__":
    main()
