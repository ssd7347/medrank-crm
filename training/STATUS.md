# Training status — updated 13 October 2026 (evening)

## Where we are

| Step | State |
|---|---|
| Training kit (`training/`) | Done, committed. 12 kit tests + 54 backend tests pass. |
| Models in Ollama | `qwen3:4b-instruct` (to train, Apache 2.0), `qwen2.5:7b` (teacher, Apache 2.0), `qwen2.5:3b` (non-commercial licence: testing only) |
| Template calls | 1,560 made → `data/train.jsonl` (1,482) + `data/val.jsonl` (78) |
| **"Before" test of `qwen3:4b-instruct`** | **Finished: 4 of 30 calls handled correctly (13%).** This is the score to beat. |
| Model-written calls (`generate.py`) | Running overnight 13→14 Oct, target 40 (progress in `data/generate.log`). About 4–7 min per attempt, many rejected. |
| Training on Colab | Not started |

`data/` is not in git; it stays on the laptop. Before-test results: calls 1–24 in `data/eval-before.log`, 25–26 in
`data/eval-qwen3_4b-instruct.jsonl` (saved per call; the resume reads both automatically).

## "Before" results (qwen3:4b-instruct, untrained): 4/30 = 13%

| Scenario | Passed | Scenario | Passed |
|---|---|---|---|
| will_act | 1/1 | verify_fail | 0/3 |
| feedback_unhappy | 1/1 | verify_retry | 0/2 |
| ask_person | 1/2 | injection | 0/3 |
| busy | 1/2 | faq | 0/3 |
| are_you_human | 0/3 | faq_unknown | 0/2 |
| opt_out | 0/2 | wrong_person | 0/2 |
| dispute, distress, confirm_attend, cannot_attend | 0/1 each | | |

Passed: `Overdue follow-up / will_act`, `Site visit reminder / busy`, `Site visit reminder / ask_person`,
`Feedback survey / feedback_unhappy`.

The untrained model's common mistakes, which the training data teaches against:
- **Replies too long to speak** (more than three sentences, up to 47 words) — 9 calls.
- **Ends without recording the outcome** (`log_call_outcome` / `opt_out` / hand-over) — 9 calls.
- **Wrong or missing tool** for the situation: no hand-over for a distressed caller, no FAQ search, no call-back.
- **Privacy slips**: looked up details after a failed identity check (verify_fail), or on a wrong number.
- Calls `get_call_context` although the context is already in the prompt.

Full lines: `data/eval-before.log` (calls 1–24), `data/eval-before-2.log` (25–26), `data/eval-before-3.log` (27–30).

## Safety nets in the CRM (13 Oct, live in `LlmBrain`)

Whatever the model does, on every call with our own AI or Claude:
- `get_call_context` is no longer offered to the model (its answer is already in the prompt).
- Replies longer than three sentences are cut to the first two plus the last (usually the question).
- If the AI says goodbye without recording an outcome, the call goes to a person for review
  (`request_human_handoff`, reason OUT_OF_SCOPE) instead of being silently retried.
- Private details were already protected: the CRM's tools refuse them until the identity check passes.

## Carry on

```bash
cd C:\Users\sivas\Desktop\AI-Call-CRM\training

# 1. (done) The before-test is finished.

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
