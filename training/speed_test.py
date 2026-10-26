"""How fast a model answers on this machine, turn by turn, as the CRM sends a real call to it.

    python speed_test.py --model ourvoice
    python speed_test.py --model qwen3:8b --no-think

Builds one example call from the templates (no saved data needed, so it also runs on Colab), then replays every
agent turn against Ollama and prints Ollama's own timings: loading, reading the new part of the prompt, writing the
reply. A phone call needs the reply to start within about 2 seconds; the first turn of a call reads the whole prompt.
"""

import argparse
import json
import random
import statistics
import time
import urllib.request

from callkit import all_scripts, tools_in_system
from scenarios import SCENARIOS, build


def chat(url: str, model: str, history: list[dict], tools: list[dict], think: bool | None) -> dict:
    body = {"model": model, "messages": tools_in_system(history, tools), "stream": False, "keep_alive": "30m",
            "options": {"temperature": 0.3, "num_ctx": 8192, "num_predict": 300}}
    if think is False:
        body["think"] = False
    start = time.time()
    req = urllib.request.Request(url + "/api/chat", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=900) as r:
        res = json.load(r)
    ns = 1e9
    return {"wall": time.time() - start, "load": res.get("load_duration", 0) / ns,
            "read_tokens": res.get("prompt_eval_count", 0), "read_s": res.get("prompt_eval_duration", 0) / ns,
            "out_tokens": res.get("eval_count", 0), "write_s": res.get("eval_duration", 0) / ns,
            "thinking": bool(res.get("message", {}).get("thinking")) or "<think>" in res.get("message", {}).get("content", "")}


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--model", required=True)
    ap.add_argument("--no-think", action="store_true", help="switch off hidden reasoning (qwen3:8b, qwen3:14b)")
    ap.add_argument("--url", default="http://localhost:11434")
    a = ap.parse_args()

    script = next(s for s in all_scripts() if s.name == "Overdue follow-up")
    rec = build(script, next(s for s in SCENARIOS if s.id == "will_act"), random.Random(5))
    msgs = rec["messages"]
    print(f"{a.model}: one '{script.name}' call, every agent turn as the CRM would send it")
    print(f"{'turn':>4} {'total s':>8} {'load s':>7} {'prompt tok':>11} {'read s':>7} {'reply tok':>10} {'write s':>8} {'tok/s':>6}")
    rows = []
    for i, m in enumerate(msgs):
        if m["role"] != "assistant" or i < 3:
            continue
        r = chat(a.url, a.model, msgs[:i], rec["tools"], False if a.no_think else None)
        rows.append(r)
        rate = r["out_tokens"] / r["write_s"] if r["write_s"] else 0
        print(f"{len(rows):>4} {r['wall']:>8.1f} {r['load']:>7.1f} {r['read_tokens']:>11} {r['read_s']:>7.1f} "
              f"{r['out_tokens']:>10} {r['write_s']:>8.1f} {rate:>6.1f}" + ("   THINKING" if r["thinking"] else ""))
    later = [r["wall"] for r in rows[1:]]
    print(f"\nFirst turn {rows[0]['wall']:.1f} s; later turns median {statistics.median(later):.1f} s, slowest {max(later):.1f} s.")
    if any(r["thinking"] for r in rows):
        print("WARNING: the model was still thinking: the times include hidden reasoning.")


if __name__ == "__main__":
    main()
