# Training status — 22 October 2026: ROUND 2 FINAL 12/30 (40%) — ROUND 1 STAYS THE BEST

| Model | Score on the 30 test calls |
|---|---|
| untrained qwen3:4b-instruct | 4/30 (13%) |
| **`ourvoice` (round 1)** | **15/30 (50%) — best so far, keep it** |
| `ourvoice-r2` (round 2) | 12/30 (40%) |

Round 2 per scenario: are_you_human 3/3, ask_person 2/2, dispute 1/1, feedback_unhappy 1/1, faq 1/3, opt_out 1/2,
verify_fail 1/3, verify_retry 1/2, wrong_person 1/2; busy 0/2, faq_unknown 0/2, injection 0/3, cannot_attend 0/1,
confirm_attend 0/1, distress 0/1, will_act 0/1. Log `data/eval-r2.log` + `data/eval-r2b.log`.
Main failures: calls end without log_call_outcome; verify_identity with the answers written inside full_name;
the identity question repeats the script (too long); one unreadable reply (Ollama 500).
**Before any round 3** (decide with the user; test everything first): find out why round 2 lost the "record the
outcome" habit (compare its transcripts with round 1's on the same tests), add the CRM safety net for answers
inside full_name and a checker rule for it, then consider 2 epochs on the round 2 data.

---

# Earlier: 21 October 2026, 10:40 pm: ROUND 2 "AFTER" TEST AT 26/30

Round 2 model built (Colab resumed at checkpoint-64 and only built the file) and loaded as **`ourvoice-r2`**
(`C:\Users\sivas\Downloads\ourvoice-r2.Q4_K_M.gguf`, GGUF v3, 398 tensors). "After" test stopped at 26 of 30 when
the laptop was shut down: **10 of 26 passed (38%)** — round 1 had 13 of 26 at the same point (final 15/30 = 50%).
Finish it: `python -u evaluate.py --model ourvoice-r2 > data/eval-r2b.log` (skips the 26 done; per-call results in
`data/eval-ourvoice-r2.jsonl`, log `data/eval-r2.log`).

Round 2 vs round 1 so far: better on dispute (✅ vs ❌) and are_you_human (2/2); worse on opt_out, busy,
wrong_person, faq_unknown — mostly **calls ending without log_call_outcome**.
**New round 2 bug:** verify_identity puts the answers inside the name, e.g.
`{"full_name": "Rahul Fernandes, answers: {'loan_account_number': '756166'}"}` → genuine callers fail the check.
Also the identity question repeats the script ("After verification, I can share the amount…") → too long.
Plan (after the test, test everything first): CRM safety net to read answers written inside full_name; checker
rule rejecting it; round 3 with 2 epochs (one pass was not enough to fix the nested `answers` format).

---

# Earlier: 15 October 2026, 1:15 pm: ROUND 2 TRAINED, MODEL FILE NOT BUILT YET

Round 2 trained on Colab (1,009 calls, 64 steps, ~55 s/step; loss 1.654 → 0.633 → 0.244 → 0.157 at step 25 →
0.115 at step 35). The runtime then disconnected while converting to GGUF, and Colab refused a new GPU (daily free
usage limit). **Saved on Google Drive (user checked): `MyDrive/ourvoice-training-r2/ourvoice-lora` and
`checkpoints/checkpoint-64`.**

**Next session (after the GPU limit resets, ~24 h):** open the same notebook (Drive `train-on-colab.ipynb`, the
"Round 2" one), Runtime → T4 GPU, upload `Desktop\Colab training\colab-kit.zip`, run cells 1–4, then cell 5 — it must
say "Carrying on from …/ourvoice-training-r2/checkpoints/checkpoint-64", skips training and only builds the model
file (~25 min) — then cell 6 and download `ourvoice-r2.Q4_K_M.gguf` from the Files panel. On the laptop:
`python make_modelfile.py C:\Users\sivas\Downloads\ourvoice-r2.Q4_K_M.gguf --name ourvoice-r2 --create`, then
`python -u evaluate.py --model ourvoice-r2 > data/eval-r2.log` and compare with round 1 (50%).
Fallback if Colab keeps refusing a GPU: build the GGUF from `ourvoice-lora` on a CPU (peft merge + llama.cpp) — new
code, test it first.

---

# Earlier: 15 October 2026 — ROUND 2 DATA READY

Round 2 templates (tested: 1,062 calls pass every check over 5 seeds; 24 kit tests OK; real-tokenizer format check
1,009 train calls, 0 problems, longest 2,323 tokens):
- the caller gives their name first and the ID only when asked again (round 1 dropped an ID given on its own);
- 10 dispute and 6 distress phrasings, a third of them before any detail is shared (hand over at once);
- more phrasings for FAQ questions mid-call, appointments (confirm / cannot attend) and payment promises;
- double weight for what round 1 failed: dispute, distress, faq, confirm_attend, cannot_attend, will_act,
  verify_fail, injection;
- each person's dates are distinct (a public date equal to a private one looked like a privacy leak).
Colab notebook: new Drive folder `MyDrive/ourvoice-training-r2` (reusing round 1's would resume at its finished
checkpoint and skip training), a Drive free-space check (≥1.5 GB) before training, ~64 steps ≈ 70 min, and the model
file renamed `ourvoice-r2.Q4_K_M.gguf` for a direct download from the Files panel. Files: `Desktop\Colab training`.
**Next:** free Drive space (delete `MyDrive/ourvoice-training/checkpoints` and its `.gguf`, empty Trash), run the
notebook, download the file, `make_modelfile.py <file> --name ourvoice-r2 --create`, `evaluate.py --model ourvoice-r2`.

---

# Earlier: 14 October 2026, 10:10 pm: "AFTER" TEST DONE — 13% → 50%

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
