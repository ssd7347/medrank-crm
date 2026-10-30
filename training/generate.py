"""Option A: a local open model writes example calls, so no outside AI service (and no API key) is involved.

    ollama pull qwen2.5:7b                      # the "teacher": bigger than the model we train, so it writes better calls
    python generate.py --count 100              # runs for hours on a laptop; stop with Ctrl+C and rerun to continue

Each call is played out against the fake CRM and checked with callkit.check(); only calls that pass every rule are
kept (data/generated.jsonl). The rest go to data/rejected.jsonl with the reasons, which is worth a look now and then.

Round 4 (29 October 2026): our own trained 8B is the teacher on Colab, talking to the same simulated caller as the
test, so the kept calls show how real callers talk (three rounds of template calls stalled at 60-65%). It gets the
private trainer notes (sim.COACH); only calls that pass every check are kept, never on the two test scripts.

    python generate.py --teacher ourvoice8b --caller qwen2.5:7b --focus dispute,interested
                       --out generated-r4.jsonl --out-dir /content/drive/MyDrive/ourvoice-r4-calls --minutes 150

(No --no-think for ourvoice8b: it is built with the plain no-thinking template and tested without the switch.)
"""

import argparse
import collections
import itertools
import random
import time
from pathlib import Path

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
    ap.add_argument("--minutes", type=float, default=None,
                    help="stop after this many minutes (on Colab, so there is time left to train)")
    ap.add_argument("--out", default="generated.jsonl", help="file for the kept calls (default generated.jsonl)")
    ap.add_argument("--out-dir", default=str(DATA),
                    help="folder for the kept and rejected calls; on Colab a Google Drive folder, so a disconnect loses nothing")
    ap.add_argument("--no-think", action="store_true", help="switch off hidden reasoning (hybrid models such as the 8B)")
    ap.add_argument("--focus", default="", help="scenarios tried 3 times as often, comma-separated (the weak ones)")
    ap.add_argument("--max-per-scenario", type=int, default=None,
                    help="stop keeping a scenario's calls after this many, so the easy ones do not crowd out the rest")
    a = ap.parse_args()

    models = installed_models(a.url)
    for m in {a.teacher, a.caller or a.teacher}:
        if m not in models and m + ":latest" not in models:
            raise SystemExit(f"Model {m} is not installed. Run:  ollama pull {m}\nInstalled: {', '.join(models)}")

    out_dir = Path(a.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    good_path, bad_path = out_dir / a.out, out_dir / "rejected.jsonl"
    kept = read_jsonl(good_path)
    done = len(kept)
    per = collections.Counter(r["meta"]["scenario"] for r in kept)
    rng = random.Random(a.seed if a.seed is not None else time.time_ns())
    focus = {f.strip() for f in a.focus.split(",") if f.strip()}
    pairs = [(s, sc) for s in all_scripts() if s.name not in HELD_OUT_SCRIPTS for sc in for_script(s)
             for _ in range(3 if sc.id in focus else 1)]
    unknown = focus - {sc.id for _, sc in pairs}
    if unknown:
        raise SystemExit(f"Unknown scenario in --focus: {', '.join(sorted(unknown))}")
    rng.shuffle(pairs)
    print(f"{done} good conversations so far; target {a.count}. Teacher {a.teacher}, caller {a.caller or a.teacher}.")
    # Rate and time left count only this run, so earlier runs' calls do not inflate them.
    started, tried, kept_now, errors = time.time(), 0, 0, 0
    for script, scenario in itertools.cycle(pairs):
        if done >= a.count:
            break
        if a.minutes is not None and time.time() - started >= a.minutes * 60:
            print(f"Time limit of {a.minutes:g} minutes reached: {kept_now} kept in this run, {done} in total.")
            break
        if a.max_per_scenario is not None:
            if all(per[sc.id] >= a.max_per_scenario for _, sc in pairs):
                print(f"Every scenario has {a.max_per_scenario} kept calls: {done} in total.")
                break
            if per[scenario.id] >= a.max_per_scenario:
                continue
        tried += 1
        t0 = time.time()
        try:
            rec, stopped = simulate(script, scenario, rng, a.teacher, a.caller or a.teacher, coach=True, url=a.url,
                                    agent_think=False if a.no_think else None)
            errors = 0
        except OSError as e:
            # One failed reply (Ollama's "HTTP 500" stopped the 29 Oct run) costs only this call; five in a row means
            # Ollama itself is down.
            errors += 1
            if errors >= 5:
                raise SystemExit(f"Could not reach Ollama at {a.url} five times in a row: {e}")
            print(f"[{done}/{a.count}] {script.name} / {scenario.id}: skipped, Ollama error ({e})")
            time.sleep(5)
            continue
        problems = ([stopped] if stopped else []) + (check(rec) if rec else [])
        took = time.time() - t0
        if rec and not problems:
            write_jsonl(good_path, [rec], "a")
            done += 1
            kept_now += 1
            per[scenario.id] += 1
        elif rec:
            rec["meta"]["problems"] = problems
            write_jsonl(bad_path, [rec], "a")
        status = "kept" if not problems else "rejected: " + "; ".join(problems[:2])
        rate = kept_now / tried
        eta = (a.count - done) * (time.time() - started) / kept_now / 60 if kept_now else float("nan")
        print(f"[{done}/{a.count}] {script.name} / {scenario.id}: {status} ({took:.0f}s, {rate:.0%} kept, ~{eta:.0f} min left)")
        if a.show and rec:
            print(transcript(rec), "\n")


if __name__ == "__main__":
    main()
