# Training status — saved 12 October 2026, 10:45 pm

## Where we are

| Step | State |
|---|---|
| Training kit (`training/`) | Done, committed. 12 kit tests + 54 backend tests pass. |
| Models in Ollama | `qwen3:4b-instruct` (to train, Apache 2.0), `qwen2.5:7b` (teacher, Apache 2.0), `qwen2.5:3b` (non-commercial licence: testing only) |
| Template calls | 1,560 made → `data/train.jsonl` (1,482) + `data/val.jsonl` (78) |
| **"Before" test of `qwen3:4b-instruct`** | **21 of 30 calls done when the laptop was shut down. 3 passed (14%).** 9 calls left. |
| Model-written calls (`generate.py`) | Not started (run overnight once the before-test is finished) |
| Training on Colab | Not started |

`data/` is not in git; it stays on the laptop (`training/data/eval-before.log` holds the 21 results).

## "Before" results so far (qwen3:4b-instruct, untrained)

Passed: `Overdue follow-up / will_act`, `Site visit reminder / busy`, `Site visit reminder / ask_person`.

The untrained model's common mistakes, which the training data teaches against:
- **Replies too long to speak** (more than three sentences, up to 47 words) — 9 calls.
- **Ends without recording the outcome** (`log_call_outcome` / `opt_out` / hand-over) — 9 calls.
- **Wrong or missing tool** for the situation: no hand-over for a distressed caller, no FAQ search, no call-back.
- **Privacy slips**: looked up details after a failed identity check (verify_fail), or on a wrong number.
- Calls `get_call_context` although the context is already in the prompt.

Full lines: `training/data/eval-before.log` (calls 1–21).

## Tomorrow: carry on

```bash
cd C:\Users\sivas\Desktop\AI-Call-CRM\training

# 1. Finish the before-test: skips calls 1-21 (read from tonight's log) and runs 22-30.
#    Log to a NEW file so tonight's log is not overwritten.
python -u evaluate.py --model qwen3:4b-instruct --resume-log data/eval-before.log > data/eval-before-2.log

# 2. Let the teacher write example calls (hours; Ctrl+C stops, rerun continues)
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
