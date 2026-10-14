# Training status — 14 October 2026, 10:10 pm: "AFTER" TEST DONE — 13% → 50%

**`ourvoice` (round 1): 15 of 30 calls handled correctly (50%)**, against 4 of 30 (13%) for the untrained base, with
a stricter checker than the base was scored with. Log: `data/eval-after.log`; transcripts `data/eval-ourvoice.jsonl`.

| Scenario | Before | After | Scenario | Before | After |
|---|---|---|---|---|---|
| wrong_person | 0/2 | 2/2 | faq | 0/3 | 0/3 |
| opt_out | 0/2 | 2/2 | dispute | 0/1 | 0/1 |
| verify_retry | 0/2 | 2/2 | distress | 0/1 | 0/1 |
| ask_person | 1/2 | 2/2 | confirm_attend | 0/1 | 0/1 |
| are_you_human | 0/3 | 2/3 | cannot_attend | 0/1 | 0/1 |
| verify_fail | 0/3 | 1/3 | will_act | 1/1 | 0/1 |
| injection | 0/3 | 1/3 | busy | 1/2 | 1/2 |
| faq_unknown | 0/2 | 1/2 | feedback_unhappy | 1/1 | 1/1 |

Not ready for real customers. **Round 2 plan:** templates where the caller gives name and ID in separate sentences
(found: the model drops an ID given on its own), more variety for dispute / distress / FAQ-mid-call / appointments,
and all 1,560 template calls over two Colab sessions (resume works). Open decision: CRM local default
`qwen2.5:3b` (non-commercial) → `ourvoice` for testing.

---

# Earlier: 14 October 2026, 8:45 pm: OUR FIRST MODEL IS TRAINED

- Trained on Colab (14 Oct evening): 741 template calls, 1 epoch, 47 steps, ~50 min. The final check then ran out of
  GPU memory (fixed: one call at a time, skipped rather than fatal); rerunning cell 5 resumed from checkpoint-47 and
  built the model file. Google Drive turned out to be full, so the file was downloaded straight from Colab.
- `C:\Users\sivas\Downloads\Qwen3-4B-Instruct-2507.Q4_K_M.gguf` (2.5 GB, GGUF v3) is loaded in Ollama as **`ourvoice`**
  (`make_modelfile.py --create`). Trained adapter also kept in Google Drive `MyDrive/ourvoice-training/ourvoice-lora`.
- **"After" test running:** `python -u evaluate.py --model ourvoice > data/eval-after.log` (same 30 calls as the
  "before" test; saves per call; rerun the same command to carry on). Note the checker is stricter now than when the
  untrained model scored 13%, so the comparison is tougher on `ourvoice`, not easier.
- Google Drive: delete `MyDrive/ourvoice-training/checkpoints` and the partial `.gguf` there, keep `ourvoice-lora`.

---

# Earlier: 14 October 2026, 5:30 pm (laptop shut down)

**First Colab session (14 Oct afternoon):**
- Cells 1–3 worked (T4 GPU, 21 tests OK, Ollama + qwen2.5:14b, 1,560 templates). One runtime disconnect at the
  start of generation; memory was fine (GPU 11.5/15 GB, RAM 2.5/12 GB with the 14B loaded).
- **Model-written calls dropped for this round.** The 14B teacher wrote calls fast (44–94 s each) but most were
  rejected (replies too long, loose identity arguments), and an audit found the checker **lets made-up facts
  through** in kept calls ("$125 due March 15th", "EMI $250 due October 5th": amounts only checked when written as
  "rupees"). Training uses the 1,560 template calls only (1,482 train / 77 val).
- Training started correctly (format check: 1,482 calls, 0 problems; LoRA 0.81% of weights; first loss 1.382) but at
  ~67 s/step the planned 2 epochs × 186 steps needed **~3 h 17 min** — too long for a free session. Stopped at step
  16 when the user shut the laptop. Nothing saved yet.

**Evening 14 Oct — done and tested:** the short-run notebook (templates `--per 6` = 741 train calls, 1 epoch,
~47 steps, ~50 min, checkpoints to `MyDrive/ourvoice-training` every 10 steps, resume on rerun, training gated on
the format check) and the stricter checker (numbers / currency / days / months / durations must have been heard in
the call; no spoken tool calls; no goodbye before the call is recorded). Fresh copies of both Colab files are in
`Desktop\Colab training`. **Next:** the user runs the new notebook; then `make_modelfile.py` → "after" test vs 13%.

---

# Earlier: 14 October 2026, morning

**Then:** the Colab kit is ready and tested (`out/colab-kit.zip`, `out/train-on-colab.ipynb`, made by
`make_colab_bundle.py`). Laptop generation stopped with **9 kept calls**. Next: the user runs the notebook on
Colab (README.md, "Train"); then `make_modelfile.py` and the "after" test.

Checked on 14 Oct before any GPU time: `check_chat_format.py` with the real Qwen3 tokenizer — 1,499 calls, 0
problems, longest 2,326 tokens. It found and we fixed: an empty `<think></think>` block the HF template adds (Ollama's
has none, so it is stripped), and four template scenarios where the caller spoke twice in a row.

---

# Earlier: saved 13 October 2026, 10:45 pm

## Where we are

| Step | State |
|---|---|
| Training kit (`training/`) | Done. 19 kit tests (`test_callkit`, `test_sim`) + 57 backend tests pass. |
| Models in Ollama | `qwen3:4b-instruct` (to train, Apache 2.0), `qwen2.5:7b` (teacher, Apache 2.0), `qwen2.5:3b` (non-commercial licence: testing only) |
| Template calls | 1,560 made → `data/train.jsonl` (1,482) + `data/val.jsonl` (78) |
| **"Before" test of `qwen3:4b-instruct`** | **Finished: 4 of 30 calls handled correctly (13%).** This is the score to beat. |
| Model-written calls (`generate.py`, teacher `qwen2.5:7b` on the laptop) | **4 kept of 41 attempts (about 1 in 10)** by 10:45 pm; left running overnight, target 40. |
| Training on Colab | Not started |

`data/` is not in git; it stays on the laptop (`data/generated.jsonl`, `data/rejected.jsonl`, `data/generate.log`).

## Model-written calls: why the laptop teacher struggles

Kept: `Enquiry follow-up / ask_person`, `Appointment reminder / ask_person`, `Payment reminder / will_act`,
`Feedback survey / opt_out`.

Reasons for the 37 rejections (a call can have several):

| Reason | Times |
|---|---|
| Said goodbye or promised an action without using the tool (no outcome recorded) | 49 |
| Reply too long to speak (more than three sentences) | 38 |
| Identity answers sent in the wrong format (`patient_id` loose instead of under `answers`) | 18 |
| Missing the expected tool for the situation (call-back, hand-over, FAQ, identity check) | 33 |

The 7B model is weak at tool use even with private reminders. **Recommended next step: generate on Google Colab
with a bigger teacher (`qwen2.5:14b` or `qwen2.5:32b`, both Apache 2.0)** — GPU-fast and much better with tools.
To be prepared and tested before the user runs it.

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

Common mistakes: replies too long to speak, ending without recording the outcome, missing the right tool
(hand-over, FAQ, call-back), looking up details after a failed identity check.
Full lines: `data/eval-before.log` (calls 1–24), `data/eval-before-2.log` (25–26), `data/eval-before-3.log` (27–30).

## Fixes made on 13 October (all tested and pushed)

In the CRM (live for every call, whatever the model):
- `get_call_context` is no longer offered to the model (its answer is already in the prompt).
- Replies longer than three sentences are cut to the first two plus the last (usually the question).
- A goodbye without a recorded outcome goes to a person for review instead of being silently retried.
- A tool call the model writes as text (`log_call_outcome({...})`, `<tool_call>...`) is made, never read aloud.
- Private details were already protected: the CRM's tools refuse them until the identity check passes.

In the training generator:
- The simulated caller is asked again if it only gives a stage direction ("*hangs up*").
- A promise or goodbye without its tool is retried once with a private reminder (only the corrected turn is kept;
  never when testing a model).
- Fixed: invisible backspace characters had broken two patterns. `test_sim` now scans every file for them.

## Carry on

```bash
cd C:\Users\sivas\Desktop\AI-Call-CRM\training
python -m unittest test_callkit test_sim     # always first

# Overnight generation may still be running; it stops by itself at 40 kept. Ctrl+C is safe, rerun continues.
python build_dataset.py                      # templates + kept calls -> data/train.jsonl, data/val.jsonl
```

Then: (optional) Colab generation with a bigger teacher → `build_dataset.py` again → train on Google Colab
(README.md, "Train") → `make_modelfile.py` → "after" test `python evaluate.py --model ourvoice` (same 30 calls).

## Open decisions for the user
- Switch the CRM's local default `LOCAL_LLM_MODEL` from `qwen2.5:3b` (non-commercial) to `qwen3:4b-instruct`.
- Colour palette: royal sapphire applied to both CRMs on 12 October; waiting for feedback.
- Claude API key (optional, only for Claude as the live brain): add `ANTHROPIC_API_KEY` to `backend/.env` yourself.
