# Training status — saved 13 October 2026 (morning)

## Where we are

| Step | State |
|---|---|
| Training kit (`training/`) | Done, committed. 12 kit tests + 54 backend tests pass. |
| Models in Ollama | `qwen3:4b-instruct` (to train, Apache 2.0), `qwen2.5:7b` (teacher, Apache 2.0), `qwen2.5:3b` (non-commercial licence: testing only) |
| Template calls | 1,560 made → `data/train.jsonl` (1,482) + `data/val.jsonl` (78) |
| **"Before" test of `qwen3:4b-instruct`** | **26 of 30 calls done, 4 passed (15%).** Stopped on purpose; calls 27–30 left. |
| Model-written calls (`generate.py`) | Started on 13 Oct, stopped on purpose before the first call finished: 0 of 100. |
| Training on Colab | Not started |

`data/` is not in git; it stays on the laptop. Before-test results: calls 1–24 in `data/eval-before.log`, 25–26 in
`data/eval-qwen3_4b-instruct.jsonl` (saved per call; the resume reads both automatically).

## "Before" results so far (qwen3:4b-instruct, untrained)

Passed: `Overdue follow-up / will_act`, `Site visit reminder / busy`, `Site visit reminder / ask_person`,
`Feedback survey / feedback_unhappy`.

The untrained model's common mistakes, which the training data teaches against:
- **Replies too long to speak** (more than three sentences, up to 47 words) — 9 calls.
- **Ends without recording the outcome** (`log_call_outcome` / `opt_out` / hand-over) — 9 calls.
- **Wrong or missing tool** for the situation: no hand-over for a distressed caller, no FAQ search, no call-back.
- **Privacy slips**: looked up details after a failed identity check (verify_fail), or on a wrong number.
- Calls `get_call_context` although the context is already in the prompt.

Full lines: `training/data/eval-before.log` (calls 1–24) and `data/eval-before-2.log` (25–26).

## Next session (13 October evening): carry on

```bash
cd C:\Users\sivas\Desktop\AI-Call-CRM\training

# 1. Finish the before-test: skips calls 1-26 and runs 27-30 (about 30 minutes).
#    Log to a NEW file so the earlier logs are not overwritten.
python -u evaluate.py --model qwen3:4b-instruct --resume-log data/eval-before.log > data/eval-before-3.log

# 2. Let the teacher write example calls (several minutes each, hours in all; Ctrl+C stops, rerun continues).
#    Run it after step 1, not at the same time: both share the laptop's CPU.
python generate.py --count 100

# 3. Rebuild the dataset with both kinds of calls
python build_dataset.py
```

Then train on Google Colab (README.md, "Train"), load the result with `make_modelfile.py`, and run the
"after" test: `python evaluate.py --model ourvoice` (same seed, same 30 test calls) to compare.

## Open decisions for the user
- Switch the CRM's local default `LOCAL_LLM_MODEL` from `qwen2.5:3b` (non-commercial) to `qwen3:4b-instruct`.
- Colour palette: royal sapphire applied to both CRMs on 12 October; waiting for feedback.
- Claude API key (optional, only for Claude as the live brain): add `ANTHROPIC_API_KEY` to `backend/.env` yourself.
