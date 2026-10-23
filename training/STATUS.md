<!-- LIVE PROGRESS START -->
## Live re-score progress (updated 23 Oct 2026, 07:16 PM by status_watch.py)
- `eval-base-fixed.log`: 8 of 30 calls done in this log, 2 passed (a resumed run only lists the calls it ran). Latest: [9] PASS Overdue follow-up / ask_person
<!-- LIVE PROGRESS END -->

# DELIVERY PLAN (agreed 23 Oct 2026): client hand-over ~23 Nov 2026, real phone calls

Brain: our own model; **checkpoint 6 Nov** — pass mark ≥ 85% of a 60-call test (with the CRM safety net), zero
private details before verification, reply starts within ~2 s on a phone; otherwise switch to the Claude API.
| Week | Work | User |
|---|---|---|
| 1 (– 30 Oct) | round 3 data; reply-speed test; phone-platform comparison; endpoint the platform calls | pick provider, start sign-up/KYC |
| 2 (– 6 Nov) | 60-call test; connect platform; first real call to the user's phone | run round 3 on Colab |
| 3 (– 13 Nov) | CRM online (model or Claude); real test calls to team phones | server/GPU account if own model |
| 4 (– 23 Nov) | client scripts/data, compliance (TRAI/DLT, hours, DND), hand-over guide, buffer | client walkthrough |

# 23 October 2026, night: ROUND 3 KIT READY (user runs it on Colab)

Built from the failed re-score transcripts (`scenarios.py`, comments marked "Round 3"):
- annoyed opt-outs ("stop calling me like this, it's annoying") → `opt_out`, not a dispute hand-over;
- a person wanted, said crossly ("why aren't you sending an actual human?") → hand-over CALLER_REQUEST;
- quieter hardship (laid off, no income, medical bills) → DISTRESS;
- "I will be coming / still planning to attend" → ACKNOWLEDGED; 30% of confirmations also ask a question →
  `search_faq`, answer, then close;
- cannot attend: 40% of callers propose their own day → the agent says it cannot rebook, never offers a date, and
  books a call-back with that wish;
- weights: dispute back to 1 (round 2's double weight → every hand-over called a dispute); ask_person and opt_out
  1 as in round 1; confirm_attend and cannot_attend 3.
Checked: 1,074 template calls pass every check over 5 seeds; 28 kit tests OK, also from a clean unzip of the kit
(1,021 train / 53 val calls, ~64 steps, ~70 min, 1 epoch). The real-tokenizer format check could not run on the laptop
(`transformers` not installed); the notebook runs it as a gate before training (must say "0 with problems").
Kit: `make_colab_bundle.py` now `ROUND = 3` → Drive folder `MyDrive/ourvoice-training-r3`, file
`ourvoice-r3.Q4_K_M.gguf`. Fresh copies in `Desktop\Colab training` (19:16).
**User:** Drive needs ≥ 1.5 GB free (else delete `MyDrive/ourvoice-training-r2/checkpoints`, keep `ourvoice-lora`,
empty Trash); upload the notebook to Colab, T4 GPU, upload colab-kit.zip, run cells 1–6, download the .gguf to
Downloads. **Then me:** `python make_modelfile.py C:\Users\sivas\Downloads\ourvoice-r3.Q4_K_M.gguf --name ourvoice-r3 --create`
and the 60-call test. To decide before that test: `evaluate.py --calls 60` fills the extra 30 with training scripts
(new callers, but scripts the model has seen) — consider holding out more scripts instead.

---

# 23 October 2026, evening: CRM SAFETY NET ADDED (call-back / identity lock-out)

Finding first: the CRM already RECORDS these outcomes inside the tools (`HandoffService.callback` → CALLBACK_REQUESTED,
the lock-out in `handoff` → VERIFICATION_FAILED). The bug was in `LlmBrain.respond`: the model's goodbye without
`log_call_outcome` raised a review hand-over, which overwrote CALLBACK_REQUESTED with HANDED_OFF and made a needless
task. Now `LlmBrain.settlesTheCall()` marks the session settled after a successful `schedule_callback` or the
lock-out, and a goodbye then simply ends the call. 2 new tests (end-to-end busy call keeps CALLBACK_REQUESTED; unit
test of both cases and the non-cases); **61 backend tests OK**.
Kit mirror: `callkit.settles_the_call()` (same rule), `FakeCrm.settled`, `sim.py` ends the call on that goodbye like
the CRM, `check(rec, crm_net=True)` accepts it (default stays strict, so training data still teaches
`log_call_outcome`; all 1,062 templates still pass). `evaluate.py` now prints both scores: with the CRM's safety net
(headline) and "the model alone" (the strict rule used for every score before today). 2 new tests, **28 kit tests OK**.
Strict rule checked: reproduces all 30 old verdicts of both rounds. From the saved transcripts (same calls):

| Model | Model alone (strict) | With the CRM safety net |
|---|---|---|
| `ourvoice` (round 1) | 17/30 | **18/30** (busy #6) |
| `ourvoice-r2` (round 2) | 15/30 | 17/30 (busy #6, #18) |

Also fixed: `evaluate.py` crashed when a model wrote "₹" (Windows cp1252 log); stdout is now UTF-8. The base
re-score had stopped after call 1 because of it; resumed with
`python -u evaluate.py --model qwen3:4b-instruct >> data/eval-base-fixed.log` (call 1 was saved before the safety
net, so it carries the strict verdict only). Nothing committed yet.

---

# 23 October 2026, evening: BASE RE-SCORE RUNNING + WHAT THE REMAINING FAILURES REALLY ARE

Running: `python -u evaluate.py --model qwen3:4b-instruct > data/eval-base-fixed.log` (old result kept as
`data/eval-qwen3_4b-instruct-ollama-tools.jsonl`); `status_watch.py` updates the section above after each call.

Looked at every failed transcript of the re-score (rounds 1 and 2). Three kinds:
1. **Real, and the CRM can make it certain:** after `schedule_callback` (or the identity lock-out after 3 tries) the
   model says goodbye without `log_call_outcome`. The live CRM then raises a review hand-over
   (`LlmBrain.respond`, "ended the call without recording an outcome"). The outcome is unambiguous in both cases, so the
   CRM could record CALLBACK_REQUESTED / VERIFICATION_FAILED itself (mirrored in the kit's FakeCrm so tests match).
2. **The checker flags behaviour that is safe:**
   - "should not call get_contact_details" (verify_fail / injection): the model looks details up as soon as the caller
     asks what the call is about. The CRM refuses (VERIFICATION_REQUIRED) or returns only public fields, so nothing
     leaks — the private-value leak check already passes. Costs one extra model turn. Training data never shows this
     call before verification (0 refusals in 1,062 calls), so the model generalised it from other scenarios.
   - "more than one goodbye after the closing tool": hand-over followed by `log_call_outcome` HANDED_OFF, then one
     goodbye — harmless in the CRM (both tools allowed), but the training data ends at the hand-over.
3. **The simulated caller did not play its part** (e.g. R2 #24 "injection": the caller never tried anything; R2 #15
   "verify_fail": public details only, never asked to verify). These are noise, not model faults.
Other real model faults: opt_out treated as a dispute, FAQ answered without `search_faq`, a day invented
("Thursday"), round 2 inventing hand-over reason `WRONG_PERSON`.
**Nothing changed yet — options to decide with the user:** (a) CRM safety net for 1; (b) whether the checker should
keep failing the safe behaviours in 2 (report both scores either way); (c) round 3 data for the real faults.

---

# 23 October 2026, morning: RE-SCORE DONE — ROUND 1 17/30 (57%), ROUND 2 15/30 (50%)

| Model | Old score (Ollama tools) | Re-scored (fixed harness) |
|---|---|---|
| untrained qwen3:4b-instruct | 4/30 | not re-scored yet |
| **`ourvoice` (round 1)** | 15/30 | **17/30 (57%) — still the best** |
| `ourvoice-r2` (round 2) | 12/30 | 15/30 (50%) |

| Scenario | R1 | R2 | Scenario | R1 | R2 |
|---|---|---|---|---|---|
| are_you_human | 3/3 | 2/3 | faq | 1/3 | 3/3 |
| ask_person | 2/2 | 0/2 | faq_unknown | 2/2 | 2/2 |
| busy | 1/2 | 0/2 | feedback_unhappy | 1/1 | 1/1 |
| cannot_attend | 0/1 | 1/1 | injection | 0/3 | 0/3 |
| confirm_attend | 0/1 | 0/1 | opt_out | 1/2 | 1/2 |
| dispute | 1/1 | 1/1 | verify_fail | 1/3 | 0/3 |
| distress | 0/1 | 0/1 | verify_retry | 1/2 | 1/2 |
| will_act | 1/1 | 1/1 | wrong_person | 2/2 | 2/2 |

What is left, now that identity checks work:
- **Asks for private details after a failed identity check** (`get_contact_details` → VERIFICATION_REQUIRED; the CRM
  refuses, nothing leaks) — most verify_fail / injection failures, both rounds (R2 0/6).
- **No outcome after `schedule_callback`** (busy, both rounds).
- Round 2 hands over with the wrong reason or invents one (`WRONG_PERSON`), and once forgot to say it is an AI.
- Round 1 sometimes says goodbye twice after the closing tool.
- Some fails are the simulated caller not playing its part (e.g. a genuine caller in "injection"); with only 30 calls
  a difference of 2 is within noise.
Logs: `data/eval-r1-fixed.log`, `data/eval-r2-fixed.log`; transcripts `data/eval-ourvoice.jsonl`,
`data/eval-ourvoice-r2.jsonl`.

**CRM fixed too (23 Oct):** `LlmClients.OpenAiCompatible` no longer sends `tools`; `toolsInText()` writes them into
the system prompt (checked identical to `callkit.tools_in_system`, 3,886 chars) and `recoverWrittenCalls` now reads
every `<tool_call>` block first. 2 new tests, 59 backend tests OK (Maven needs JAVA_HOME = Adoptium jdk-21); a one-off
real call to `ourvoice` through the CRM client gave correct nested answers.

**Next (decide with the user):** re-score the untrained base; round 3 from round 1's recipe, with more failed-verification and busy calls rather than
double weight on dispute/distress, or CRM safety nets for the two habits above.

---

# 22 October 2026, late: HARNESS FIXED, BOTH ROUNDS BEING RE-SCORED

`callkit.ollama_chat` no longer passes `tools` to Ollama: `tools_in_system()` writes the tool list into the system
message exactly like the training text, and `read_tool_call_tags()` reads the `<tool_call>` blocks from the reply
(older written-call recovery kept as a fallback). 2 new tests, 26 kit tests OK; smoke test on the Sanjay call gives
`{"full_name": "Sanjay Chandran", "answers": {"loan_account_number": "407122"}}`.
Old results kept as `data/eval-ourvoice-ollama-tools.jsonl` and `data/eval-ourvoice-r2-ollama-tools.jsonl`.
**Re-score running** (round 1 first, then round 2; rerun the same command after a shutdown, it carries on):
`python -u evaluate.py --model ourvoice > data/eval-r1-fixed.log`, then
`python -u evaluate.py --model ourvoice-r2 > data/eval-r2-fixed.log`.
**Progress at 9:22 pm:** round 1 call 1 PASS (will_act — it failed in the old run), call 2 FAIL (verify_retry: "more
than one goodbye after the closing tool"). About 5–10 min per call. Internet goes off at 11 pm; the run is local and
keeps going if the laptop stays on (plugged in, sleep = Never). If it was shut down, rerun the two commands above.
**Tomorrow:** read `data/eval-r1-fixed.log` and `data/eval-r2-fixed.log`, compare with the old 15/30 and 12/30 per
scenario, then decide: CRM fix, base re-score, round 3.
Note the untrained base's 4/30 was also scored through Ollama's tool support (re-score it too for a fair baseline).
The CRM backend still passes `tools` (`LlmClients.OpenAiCompatible.body`) — same fix pending there.

---

# 22 October 2026, evening: THE full_name BUG IS OLLAMA, NOT THE MODEL

Found by sending the same call to `ourvoice-r2` both ways (6 samples each, scripts were in the session scratchpad):
- **Raw prompt (exact training format, `/api/generate raw`): 6/6 correct** —
  `{"full_name": "Sanjay Chandran", "answers": {"loan_account_number": "407122"}}`.
- **Through Ollama's tool support (`/api/chat` or `/v1/chat/completions` with `tools`, Ollama 0.40.5): 6/6 broken** —
  `{"full_name": "Sanjay Chandran, answers: {"}`. Ollama's tool-call reader mangles the nested `answers` object.
  (Ollama also drops `additionalProperties` from the schema it shows — prompt token count matches exactly — but the
  model still answers correctly with that shortened schema raw, so the reader is the culprit.)
- Round 1 has the same bug (EMI injection R1 transcript), so **both scores (15/30, 12/30) are pulled down** by genuine
  callers failing the identity check; the round 1 vs round 2 comparison is not reliable yet.
- **Workaround tested (2/2):** put the tool list in the system message in the training format, do NOT pass `tools`,
  and read `<tool_call>…</tool_call>` from the text ourselves → correct nested answers. The CRM already reads written
  tool calls (`LlmClients.recoverWrittenCalls`); the change is in `OpenAiCompatible.body` (local model only).
  Evaluation harness: `callkit.py` around line 703 (`/v1/chat/completions` with `body["tools"]`).

Separate, genuinely the model's: after `schedule_callback` it says goodbye without `log_call_outcome` — raw output does
the same (3 of 4 samples), although every busy training call does schedule → log → goodbye. One epoch did not fix it.
Also round 2 over-uses `request_human_handoff` (opt_out → "dispute", wrong_person → "distress"), likely from the double
weight on dispute/distress.

**Next (needs the user's go-ahead):** apply the workaround in `callkit.py` (+ test), rerun the 30-call test for
`ourvoice` and `ourvoice-r2` to get true scores, then the same change in the CRM backend (+ tests). Only then decide
round 3 (2 epochs; CRM safety net that records CALLBACK_REQUESTED when a call ends after a successful callback with no
outcome).

---

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
