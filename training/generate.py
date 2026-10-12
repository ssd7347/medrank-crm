"""Option A: a local open model writes example calls, so no outside AI service (and no API key) is involved.

    ollama pull qwen2.5:7b                      # the "teacher": bigger than the model we train, so it writes better calls
    python generate.py --count 100              # runs for hours on a laptop; stop with Ctrl+C and rerun to continue

Each call is played out against the fake CRM and checked with callkit.check(); only calls that pass every rule are
kept (data/generated.jsonl). The rest go to data/rejected.jsonl with the reasons, which is worth a look now and then.
"""

import argparse
import itertools
import random
import time

from callkit import DATA, all_scripts, check, installed_models, read_jsonl, write_jsonl
from scenarios import HELD_OUT_SCRIPTS, for_script
from sim import simulate, transcript


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--count", type=int, default=100, help="good conversations wanted in total (default 100)")
    ap.add_argument("--teacher", default="qwen2.5:7b", help="model that plays the agent (default qwen2.5:7b)")
    ap.add_argument("--caller", default=None, help="model that plays the caller (default: the teacher)")
    ap.add_argument("--url", default="http://localhost:11434")
    ap.add_argument("--seed", type=int, default=None)
    ap.add_argument("--show", action="store_true", help="print every conversation")
    a = ap.parse_args()

    models = installed_models(a.url)
    for m in {a.teacher, a.caller or a.teacher}:
        if m not in models and m + ":latest" not in models:
            raise SystemExit(f"Model {m} is not installed. Run:  ollama pull {m}\nInstalled: {', '.join(models)}")

    good_path, bad_path = DATA / "generated.jsonl", DATA / "rejected.jsonl"
    done = len(read_jsonl(good_path))
    rng = random.Random(a.seed if a.seed is not None else time.time_ns())
    pairs = [(s, sc) for s in all_scripts() if s.name not in HELD_OUT_SCRIPTS for sc in for_script(s)]
    rng.shuffle(pairs)
    print(f"{done} good conversations so far; target {a.count}. Teacher {a.teacher}, caller {a.caller or a.teacher}.")
    started, tried = time.time(), 0
    for script, scenario in itertools.cycle(pairs):
        if done >= a.count:
            break
        tried += 1
        t0 = time.time()
        try:
            rec, stopped = simulate(script, scenario, rng, a.teacher, a.caller or a.teacher, coach=True, url=a.url)
        except OSError as e:
            raise SystemExit(f"Could not reach Ollama at {a.url}: {e}")
        problems = ([stopped] if stopped else []) + (check(rec) if rec else [])
        took = time.time() - t0
        if rec and not problems:
            write_jsonl(good_path, [rec], "a")
            done += 1
        elif rec:
            rec["meta"]["problems"] = problems
            write_jsonl(bad_path, [rec], "a")
        status = "kept" if not problems else "rejected: " + "; ".join(problems[:2])
        rate = done / max(tried, 1)
        eta = (a.count - done) * (time.time() - started) / max(done, 1) / 60 if done else float("nan")
        print(f"[{done}/{a.count}] {script.name} / {scenario.id}: {status} ({took:.0f}s, {rate:.0%} kept, ~{eta:.0f} min left)")
        if a.show and rec:
            print(transcript(rec), "\n")


if __name__ == "__main__":
    main()
