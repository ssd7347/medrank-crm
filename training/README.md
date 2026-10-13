# Training our own voice-agent model

This folder teaches an open model to be the CRM's AI calling agent. No outside AI service or API key is involved,
and nothing here needs to be installed for the data steps: they use only Python 3 and the local Ollama.

```
make_templates.py ─┐
                   ├─> build_dataset.py ─> train.jsonl ─> finetune.py (Colab GPU) ─> ourvoice.gguf ─> make_modelfile.py ─> Ollama "ourvoice"
generate.py ───────┘                                                                                                       │
                                                                                     evaluate.py: base model vs ourvoice <──┘
```

## What the model learns from

Every example is a whole phone call: the real system prompt (the call script plus the voice rules plus the call
context), what the caller says, every tool the agent uses, what each tool returns, and what the agent says.

* **Templates** (`make_templates.py`, option B): hand-written call paths in `scenarios.py`, for all 15 scripts (the
  5 built-in ones and the 10 from the industry starter packs) and 21 kinds of call: promises to pay, busy, wrong
  number, opt-out, dispute, distress, asks for a person, fails or retries the identity check, general questions,
  questions nobody can answer, attempts to trick the agent, "are you a robot?", feedback, and calls coming in.
  They take seconds and are correct by construction. **Edit the phrases in `scenarios.py` to change how the agent
  talks.**
* **Model-written calls** (`generate.py`, option A): a bigger local model (the "teacher") plays the agent, another
  plays the caller, and the tools run against a fake CRM. These are more varied and natural, but slow on a laptop.
* **Your own examples** (optional): put hand-written calls in `data/hand_written.jsonl` (same format); they count
  three times.

Every call, from any source, goes through `callkit.check()` before it can be trained on. A call is rejected if the
agent shares a private detail before the identity check, repeats an ID or date of birth, says an amount no tool
returned, uses markdown or more than three sentences, claims to be human, uses a tool wrongly, or ends without
recording how the call went. `spec/agent-spec.json` holds the live agent's exact rules and tools; the backend test
`TrainingSpecTest` fails if the code and this file ever differ.

## Licence: the base model must allow commercial use

We sell the CRM, so the model we train must be licensed for commercial use.

| Model | Licence | Use it for |
|---|---|---|
| `qwen3:4b-instruct` (Qwen3-4B-Instruct-2507) | Apache 2.0 | **the model we train** (2.5 GB, about as fast as the 3B) |
| `qwen2.5:7b` | Apache 2.0 | the teacher that writes example calls (4.7 GB) |
| `qwen2.5:3b` | **Qwen Research Licence: non-commercial only** | testing only, never for customers |

## Steps

```bash
cd training
python -m unittest test_callkit test_sim     # the checks and the simulator are tested (run after every change)
ollama pull qwen2.5:7b                       # teacher, once
ollama pull qwen3:4b-instruct                # base of our model, once

python make_templates.py --per 12            # ~1,500 template calls, a few seconds
python generate.py --count 100               # model-written calls: leave it running (Ctrl+C stops, rerun continues)
python build_dataset.py                      # -> data/train.jsonl and data/val.jsonl

python evaluate.py --model qwen3:4b-instruct # score BEFORE training, to compare with later
```

### Train (free, on Google Colab)

The laptop has no NVIDIA GPU, so training runs on Colab's free T4 GPU. The data is made-up people only.

1. Open <https://colab.research.google.com>, **New notebook**, then **Runtime → Change runtime type → T4 GPU**.
2. In the Files panel on the left, upload `data/train.jsonl`, `data/val.jsonl` and `finetune.py`.
3. Run these two cells:
   ```
   !pip install unsloth
   !python finetune.py
   ```
   About 30–60 minutes. At the end it shows one reply from the trained model.
4. Download the `.gguf` file from `out/ourvoice-gguf/` (about 2.5 GB).

### Use the trained model

```bash
python make_modelfile.py C:\Users\sivas\Downloads\<the file>.gguf --create
python evaluate.py --model ourvoice          # score AFTER training: compare with the BEFORE score
```

Then in `backend/.env` set `AI_BRAIN=LOCAL` and `LOCAL_LLM_MODEL=ourvoice`, and restart the backend. Try it in the
CRM under **AI voice agent → Test call** before using it on a real call.

## Improving it over time

* Read failed calls with `python evaluate.py --model ourvoice --show`, and `data/rejected.jsonl` from generate.py.
* A new kind of call: add a scenario (a `flow`, a caller `brief`, and what it must `expect`) to `scenarios.py`.
* A new industry: add a starter pack in `backend/src/main/resources/packs/`; its scripts are picked up here too.
* Later, real calls (with customers' consent and personal details removed) are the best training data of all.
