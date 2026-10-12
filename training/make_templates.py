"""Option B: writes hand-built example calls from scenarios.py, for every script and every scenario that fits it.

    python make_templates.py --per 12

Takes seconds and needs no model. Every conversation is checked; the script stops if any template breaks a rule,
because then the template itself needs fixing (it would teach the model a mistake).
"""

import argparse
import collections
import random

from callkit import DATA, all_scripts, check, write_jsonl
from scenarios import HELD_OUT_SCRIPTS, build, for_script


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--per", type=int, default=12, help="variations of each script x scenario pair (default 12)")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--out", default=str(DATA / "templates.jsonl"))
    a = ap.parse_args()

    rng = random.Random(a.seed)
    rows, bad, counts = [], [], collections.Counter()
    for script in all_scripts():
        if script.name in HELD_OUT_SCRIPTS:
            continue
        for scenario in for_script(script):
            for _ in range(a.per):
                rec = build(script, scenario, rng)
                problems = check(rec)
                if problems:
                    bad.append((script.name, scenario.id, problems))
                    continue
                rows.append(rec)
                counts[scenario.id] += 1
    if bad:
        for name, sid, problems in bad[:15]:
            print(f"BROKEN TEMPLATE {name} / {sid}: {problems}")
        raise SystemExit(f"{len(bad)} template conversations broke the rules; fix scenarios.py")
    write_jsonl(__import__("pathlib").Path(a.out), rows)
    print(f"Wrote {len(rows)} conversations to {a.out}")
    for sid, n in sorted(counts.items()):
        print(f"  {sid:<18} {n}")


if __name__ == "__main__":
    main()
