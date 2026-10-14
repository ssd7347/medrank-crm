"""Checks, before any GPU time is spent, that finetune.py lays our calls out correctly for the base model.

    pip install transformers jinja2            # in any venv; no PyTorch needed
    python check_chat_format.py                # downloads only the tokenizer files (~11 MB)

For the base model's tokenizer it renders calls from data/train.jsonl exactly as finetune.py does, then checks:
* tool calls come out as <tool_call>{"name": ..., "arguments": {...}}</tool_call> with the right JSON,
* tool results and the caller's words are inside user turns (context, not learnt),
* the part that is learnt (what train_on_responses_only keeps) is exactly the agent's replies and tool calls,
* every call fits in finetune.py's 4,096-token limit.
"""

import argparse
import json
import re
import sys
from pathlib import Path

from transformers import AutoTokenizer

from callkit import render_for_training

DATA = Path(__file__).resolve().parent / "data"
INSTRUCTION, RESPONSE = "<|im_start|>user\n", "<|im_start|>assistant\n"


def learnt_parts(text: str) -> list[str]:
    """What train_on_responses_only trains on: from each assistant marker up to the next user marker."""
    parts, i = [], 0
    while (start := text.find(RESPONSE, i)) >= 0:
        start += len(RESPONSE)
        end = text.find(INSTRUCTION, start)
        parts.append(text[start:] if end < 0 else text[start:end])
        i = start if end < 0 else end
        if end < 0:
            break
    return parts


def check_row(tok, row: dict, max_len: int) -> list[str]:
    problems = []
    text = render_for_training(tok, row)
    learnt = "".join(learnt_parts(text))
    for m in row["messages"]:
        content = m.get("content") or ""
        if m["role"] == "assistant":
            if content and content not in learnt:
                problems.append(f"agent reply not learnt: {content[:60]!r}")
            for c in m.get("tool_calls") or []:
                fn = c["function"]
                found = [json.loads(x) for x in re.findall(r"<tool_call>\s*(\{.*?\})\s*</tool_call>", learnt, re.S)]
                if {"name": fn["name"], "arguments": fn["arguments"]} not in found:
                    problems.append(f"tool call missing or malformed: {fn['name']}")
        elif m["role"] == "user" and f"{INSTRUCTION}{content}<|im_end|>" not in text:
            problems.append(f"caller words not in their own user turn: {content[:60]!r}")
        elif m["role"] == "tool" and f"<tool_response>\n{content}\n</tool_response>" not in text:
            problems.append(f"tool result not given back as a tool response: {content[:60]!r}")
    # Context must never be learnt: no user turn and no tool response inside what is trained on.
    if "<tool_response>" in learnt or "<|im_start|>user" in learnt or "<|im_start|>system" in learnt:
        problems.append("context (caller, tool results or script) inside the learnt part")
    if "<think>" in learnt:
        problems.append("thinking tags in the learnt part")
    n = len(tok(text, add_special_tokens=False)["input_ids"])
    if n > max_len:
        problems.append(f"{n} tokens, over the {max_len} limit")
    return problems


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base", default="unsloth/Qwen3-4B-Instruct-2507", help="the tokenizer finetune.py uses")
    ap.add_argument("--max-len", type=int, default=4096)
    ap.add_argument("--show", action="store_true", help="print one rendered call")
    a = ap.parse_args()

    tok = AutoTokenizer.from_pretrained(a.base)
    rows = [json.loads(line) for line in (DATA / "train.jsonl").read_text(encoding="utf-8").splitlines() if line.strip()]
    if a.show:
        print(render_for_training(tok, rows[0])[-2500:])
    bad, longest = 0, 0
    for i, row in enumerate(rows):
        problems = check_row(tok, row, a.max_len)
        if problems:
            bad += 1
            if bad <= 5:
                print(f"call {i}: {problems[:3]}")
    longest = max(len(tok(render_for_training(tok, r), add_special_tokens=False)["input_ids"]) for r in rows)
    print(f"{len(rows)} calls checked with {a.base}: {bad} with problems; longest {longest} tokens (limit {a.max_len}).")
    sys.exit(1 if bad else 0)


if __name__ == "__main__":
    main()
