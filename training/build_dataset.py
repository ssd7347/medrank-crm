"""Merges the template calls and the model-written calls into the files the fine-tuning step reads.

    python build_dataset.py

Re-checks every conversation (so hand edits to the data files are checked too), drops duplicates, and writes
data/train.jsonl and data/val.jsonl: one call per line as {"messages": [...], "tools": [...]}, the chat format
Qwen's tokenizer expects.
"""

import argparse
import collections
import hashlib
import json
import random

from callkit import DATA, TOOLS, check, read_jsonl, write_jsonl


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--val", type=float, default=0.05, help="share kept back to watch for over-training (default 0.05)")
    ap.add_argument("--generated-weight", type=int, default=2,
                    help="how many times each model-written call is repeated: they are fewer but more varied (default 2)")
    ap.add_argument("--seed", type=int, default=11)
    a = ap.parse_args()

    # Every generated*.jsonl counts: the laptop's and Colab's model-written calls alike.
    sources = {"templates.jsonl": 1, **{p.name: a.generated_weight for p in sorted(DATA.glob("generated*.jsonl"))},
               "hand_written.jsonl": 3}
    rows: list[tuple[dict, int]] = []
    seen, dropped = set(), collections.Counter()
    for name, weight in sources.items():
        for rec in read_jsonl(DATA / name):
            problems = check(rec)
            if problems:
                dropped[f"{name}: {problems[0]}"] += 1
                continue
            key = hashlib.sha1(json.dumps(rec["messages"][1:], sort_keys=True).encode()).hexdigest()
            if key in seen:
                dropped[f"{name}: duplicate"] += 1
                continue
            seen.add(key)
            rows.append((rec, weight))
    if not rows:
        raise SystemExit("No conversations found. Run make_templates.py (and generate.py) first.")

    rng = random.Random(a.seed)
    rng.shuffle(rows)
    # Validation calls are distinct conversations, never repeats of training ones.
    cut = max(1, int(len(rows) * a.val))
    val = [rec for rec, _ in rows[:cut]]
    train = [rec for rec, w in rows[cut:] for _ in range(w)]
    rng.shuffle(train)

    def strip(rec):
        # Always today's tool list: calls written before a tool changed still train the model on the current tools.
        return {"messages": rec["messages"], "tools": TOOLS}

    write_jsonl(DATA / "train.jsonl", map(strip, train))
    write_jsonl(DATA / "val.jsonl", map(strip, val))
    by = collections.Counter(r["meta"]["scenario"] for r in train)
    tokens = sum(len(json.dumps(r["messages"])) for r in train) // 4
    print(f"train.jsonl: {len(train)} calls (about {tokens:,} tokens before tools), val.jsonl: {len(val)} calls")
    for sid, n in sorted(by.items()):
        print(f"  {sid:<18} {n}")
    for why, n in dropped.most_common(10):
        print(f"  dropped {n} x {why}")


if __name__ == "__main__":
    main()
