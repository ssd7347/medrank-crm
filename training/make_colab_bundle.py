"""Packs everything Google Colab needs into two files in training/out/:

    python make_colab_bundle.py

* colab-kit.zip            the training kit and the industry packs
* train-on-colab.ipynb     the notebook: tests, Google Drive, training data, a format check, training (progress saved
                           to Drive, so a disconnect does not lose it), and the trained model copied to Drive

Upload the notebook to Colab (File -> Upload notebook), then upload colab-kit.zip in its Files panel, and run the
cells from the top. See README.md, "Train".

This round trains on the template calls only: model-written calls are left out until the checker had caught every
made-up fact in them (14 October: some invented amounts and dates got through).
"""

import json
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
OUT = HERE / "out"

ROUND = 3
PER = 6                     # variations of each script x scenario (x2-3 for weak ones): ~1,020 calls, ~64 steps, ~70 min
EPOCHS = 1                  # round 1 (the best so far) used 1; round 2's loss was already ~0.1 by step 35
# Survives a disconnect. A new folder per round: training "carries on" from any checkpoint it finds, so reusing
# an earlier round's folder would skip this round's training entirely.
KEEP = f"/content/drive/MyDrive/ourvoice-training-r{ROUND}"
GGUF = f"ourvoice-r{ROUND}.Q4_K_M.gguf"
NEED_GB = 1.5               # Drive space for two checkpoints and the trained adapter
KIT = "/content/kit/training"
R4_CALLS = HERE / "data" / "generated-r4.jsonl"


def kit_files() -> list[tuple[Path, str]]:
    files = [(p, f"training/{p.name}") for p in sorted(HERE.glob("*.py")) if not p.name.startswith("_")]
    # Round 4: the model-written calls that passed every check and a read-through (30 Oct); build_dataset.py
    # finds every data/generated*.jsonl.
    if R4_CALLS.exists():
        files.append((R4_CALLS, f"training/data/{R4_CALLS.name}"))
    files += [(HERE / "README.md", "training/README.md"), (HERE / "spec" / "agent-spec.json", "training/spec/agent-spec.json")]
    packs = REPO / "backend" / "src" / "main" / "resources" / "packs"
    files += [(p, f"backend/src/main/resources/packs/{p.name}") for p in sorted(packs.iterdir()) if p.is_file()]
    return files


def md(text: str) -> dict:
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip().splitlines(keepends=True)}


def code(text: str) -> dict:
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
            "source": text.strip().splitlines(keepends=True)}


def notebook() -> dict:
    # Every cell after the first goes to the kit folder itself: Colab sometimes restarts Python between cells.
    here = f"%cd {KIT}"
    cells = [
        md(f"""
# Train our own voice-agent model

Round {ROUND}. Free Google Colab GPU, **about 1½ hours**. Run the cells **in order, from the top**, one at a time.

1. **Runtime → Change runtime type → T4 GPU**, then **Save**.
2. Upload **colab-kit.zip** in the Files panel on the left (folder icon).
3. Run each cell with ▶ and wait for it to finish before the next.

Keep this tab open and the laptop awake until the last cell is done. If Colab disconnects anyway: upload
colab-kit.zip again and run the cells from the top — training carries on from the progress saved in Google Drive.

Made-up people only. Base model `Qwen3-4B-Instruct-2507` (Apache 2.0: commercial use allowed).
"""),
        md("## 1. Check the GPU and unpack the kit"),
        code(f"""
!nvidia-smi --query-gpu=name,memory.total --format=csv
!unzip -o -q /content/colab-kit.zip -d /content/kit && echo "Kit unpacked"
{here}
!python -m unittest test_callkit test_sim 2>&1 | tail -3
"""),
        md("## 2. Connect Google Drive\nTraining progress and the finished model are kept here. Colab asks you to allow access."),
        code(f"""
import shutil
from google.colab import drive
drive.mount("/content/drive")
free_gb = shutil.disk_usage("/content/drive/MyDrive").free / 1e9
print(f"Google Drive free space: {{free_gb:.1f}} GB")
if free_gb < {NEED_GB}:
    raise SystemExit("Not enough Google Drive space (need {NEED_GB} GB). In Drive, delete the checkpoints folders inside "
                     "MyDrive/ourvoice-training and MyDrive/ourvoice-training-r2 (keep ourvoice-lora) and any .gguf file there, "
                     "empty the Trash, then run this cell again.")
!mkdir -p {KEEP} && echo "Saving to {KEEP}"
"""),
        md("## 3. Install the training tool (3–5 minutes)"),
        code("!pip install -q unsloth 2>&1 | tail -1"),
        md("## 4. Make the training data and check it (about 1 minute)"),
        code(f"""
{here}
!python make_templates.py --per {PER} | head -1
!python build_dataset.py | head -1
!python check_chat_format.py > check.log 2>&1; tail -1 check.log
"""),
        md(f"""
## 5. Train (about 70 minutes)

Progress is saved to Google Drive every 10 steps. If the session ends, run cells 1–4 again and then this one:
it carries on from the last saved step.
"""),
        code(f"""
{here}
!if grep -q " 0 with problems" check.log; then python finetune.py --train data/train.jsonl --val data/val.jsonl --epochs {EPOCHS} --keep {KEEP}; else echo "Not trained: run cell 4 first; it must say '0 with problems'"; fi
"""),
        md(f"""
## 6. Download the trained model

About 2.5 GB. This cell gives it a clear name; then download it from the **Files** panel on the left: open the
folder it names, right-click **{GGUF}** → **Download**, and keep this tab open until it finishes.
"""),
        code(f"""
import glob, os, shutil
found = [f for f in glob.glob("{KIT}/**/*.gguf", recursive=True) if not f.endswith("{GGUF}")]
if not found and not glob.glob("{KIT}/**/{GGUF}", recursive=True):
    print("No .gguf file found: send a screenshot of cell 5")
for f in found:
    target = os.path.join(os.path.dirname(f), "{GGUF}")
    shutil.move(f, target)
for f in glob.glob("{KIT}/**/{GGUF}", recursive=True):
    print(f"Ready to download ({{os.path.getsize(f) / 1e9:.1f}} GB):", f.replace("/content/", "Files panel → "))
"""),
    ]
    return {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": []},
                                         "kernelspec": {"display_name": "Python 3", "name": "python3"},
                                         "language_info": {"name": "python"}},
            "nbformat": 4, "nbformat_minor": 0}


# Bigger free models to compare with ours (Apache 2.0). 8B and 14B are "hybrid" models that reason before answering
# unless told not to (--no-think); qwen3:4b-instruct is our model's base, so its speed is ours.
CALLER = "qwen2.5:7b"
CANDIDATES = [("qwen3:4b-instruct", False), ("qwen3:8b", True), ("qwen3:14b", True)]   # (model, needs --no-think)


def test_notebook() -> dict:
    here = f"%cd {KIT}"
    no_think = [m for m, nt in CANDIDATES if nt]
    pulls = "\n".join(f"!ollama pull {m} 2>&1 | tail -1" for m in [CALLER] + [m for m, _ in CANDIDATES])
    speed = "\n".join(f"stop_all()\n!python -u speed_test.py --model {m}{' --no-think' if nt else ''}\n!ollama ps"
                      for m, nt in CANDIDATES)
    evals = "\n".join(f"!python -u evaluate.py --model {m} --no-think --calls 30 > data/eval-{m.replace(':', '_')}.log 2>&1; "
                      f"tail -20 data/eval-{m.replace(':', '_')}.log" for m in no_think)
    cells = [
        md(f"""
# Test bigger free models: speed on a GPU, and the same 30 test calls

Free Google Colab GPU, **about 1 hour**. Nothing is trained and nothing is saved to Google Drive.
Run the cells **in order, from the top**, one at a time.

1. **Runtime → Change runtime type → T4 GPU**, then **Save**.
2. Upload **colab-kit.zip** in the Files panel on the left (folder icon).
3. Run each cell with ▶ and wait for it to finish before the next. Keep this tab open.

Models: {', '.join(m for m, _ in CANDIDATES)} (all Apache 2.0); the test caller is {CALLER}.
"""),
        md("## 1. Check the GPU and unpack the kit"),
        code(f"""
!nvidia-smi --query-gpu=name,memory.total --format=csv
!unzip -o -q /content/colab-kit.zip -d /content/kit && echo "Kit unpacked"
{here}
!python -m unittest test_callkit test_sim 2>&1 | tail -3
"""),
        md("## 2. Start Ollama and download the models (5–10 minutes)"),
        code(f"""
!apt-get -qq install -y zstd pciutils > /dev/null
!curl -fsSL https://ollama.com/install.sh | sh > /dev/null 2>&1 && echo "Ollama installed"
import os, subprocess, time, urllib.request
env = dict(os.environ, OLLAMA_CONTEXT_LENGTH="8192", OLLAMA_MAX_LOADED_MODELS="2")
subprocess.Popen(["ollama", "serve"], env=env, stdout=open("/content/ollama.log", "w"), stderr=subprocess.STDOUT)
for _ in range(60):
    try:
        urllib.request.urlopen("http://localhost:11434/api/tags", timeout=2)
        print("Ollama is running")
        break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit("Ollama did not start: see /content/ollama.log")
!ollama --version
{pulls}
"""),
        md("## 3. Check that thinking is switched off (1 minute)\nMust end with **Thinking is off**. Otherwise stop and send a screenshot."),
        code(f"""
{here}
# The same request evaluate.py sends (callkit.ollama_chat, think=False): any reasoning, in the text or in a field
# of its own, means thinking is still on.
import json, urllib.request
for m in {no_think!r}:
    body = {{"model": m, "messages": [{{"role": "user", "content": "Say hello in five words."}}], "max_tokens": 60,
            "stream": False, "reasoning_effort": "none"}}
    req = urllib.request.Request("http://localhost:11434/v1/chat/completions", json.dumps(body).encode(), {{"Content-Type": "application/json"}})
    res = json.load(urllib.request.urlopen(req, timeout=300))
    msg = res["choices"][0]["message"]
    text = msg.get("content") or ""
    print(m, "->", repr(text[:100]), "| other fields:", [k for k in msg if k not in ("role", "content")],
          "| reply tokens:", res.get("usage", {{}}).get("completion_tokens"))
    if "<think>" in text or not text.strip() or any(msg.get(k) for k in msg if "reason" in k or "think" in k):
        raise SystemExit(m + " is still thinking (or said nothing): send a screenshot of this cell")
print("Thinking is off")
"""),
        md("## 4. Speed: each model alone on the GPU (about 5 minutes)\n`ollama ps` under each one must say **100% GPU**."),
        code(f"""
{here}
import subprocess
def stop_all():
    for line in subprocess.run(["ollama", "ps"], capture_output=True, text=True).stdout.splitlines()[1:]:
        if line.split():
            subprocess.run(["ollama", "stop", line.split()[0]])
{speed}
"""),
        md(f"""
## 5. The same 30 test calls (about 20 minutes per model)

{no_think[0]} runs first. If {no_think[-1]} then takes **more than 3 minutes per call** (it may not fit on the GPU
next to the test caller), stop it with ■ and go on to cell 6: the {no_think[0]} result is the important one.
"""),
        code(f"""
{here}
import subprocess
for line in subprocess.run(["ollama", "ps"], capture_output=True, text=True).stdout.splitlines()[1:]:
    if line.split():
        subprocess.run(["ollama", "stop", line.split()[0]])
!mkdir -p data
{evals}
"""),
        md("## 6. Download the results\nThen right-click **test-results.zip** in the Files panel → **Download**, and send it to Claude (a few hundred KB)."),
        code(f"""
{here}
!cd data && zip -q /content/test-results.zip eval-*.log eval-*.jsonl && ls -l /content/test-results.zip
"""),
    ]
    return {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": []},
                                         "kernelspec": {"display_name": "Python 3", "name": "python3"},
                                         "language_info": {"name": "python"}},
            "nbformat": 4, "nbformat_minor": 0}


# Qwen3-8B trained on the round 3 data (Apache 2.0). Untrained it scored 14/30 against the 4B's 6/30 (26 Oct).
BASE8 = "unsloth/Qwen3-8B"
NAME8 = "ourvoice8b"
KEEP8 = f"/content/drive/MyDrive/ourvoice-training-8b-r{ROUND}"
RESULTS8 = f"{KEEP8}/results"
NEED8_GB = 3.0              # checkpoints (~1 GB each, two kept) and the adapter; the 5 GB model file only if room


def notebook_8b(keep: str = KEEP8, name: str = NAME8, per: int = PER, label: str = f"Round {ROUND} data",
                hours: str = "~2 h", total: str = "4 hours", extra: list | None = None) -> dict:
    results = f"{keep}/results"
    here = f"%cd {KIT}"
    find_gguf = (f'import glob\nlocal = sorted(glob.glob("{KIT}/out8b*/**/*.gguf", recursive=True))\n'
                 f'saved = sorted(glob.glob("{keep}/*.gguf"))\ngguf = (local or saved or [None])[0]\n'
                 f'print("Model file:", gguf)')
    cells = [
        md(f"""
# Train the 8B model and test it, all on Colab

{label} on **Qwen3-8B** (Apache 2.0), then the 60 test calls on the GPU. About **{total}** in all:
training {hours}, building the model file ~30–45 min, test ~1 h. It can be spread over several sessions:
**everything slow is saved to Google Drive** (`{keep}`): training progress every 10 steps, the trained adapter,
every finished test call, and the model file when Drive has room.

1. **Runtime → Change runtime type → T4 GPU**, then **Save**.
2. Upload **colab-kit.zip** in the Files panel on the left (folder icon).
3. Run the cells **in order, one at a time**. Keep this tab open; click in it now and then.

**After a disconnect:** reconnect, upload colab-kit.zip again and run the cells from the top. Training and the test
carry on where they stopped; cell 5 skips finished training and only rebuilds the model file if it was not saved.
"""),
        md("## 1. Check the GPU and unpack the kit"),
        code(f"""
!nvidia-smi --query-gpu=name,memory.total --format=csv
!unzip -o -q /content/colab-kit.zip -d /content/kit && echo "Kit unpacked"
{here}
!python -m unittest test_callkit test_sim 2>&1 | tail -3
"""),
        md("## 2. Connect Google Drive\nColab asks you to allow access."),
        code(f"""
import shutil
from google.colab import drive
drive.mount("/content/drive")
free_gb = shutil.disk_usage("/content/drive/MyDrive").free / 1e9
print(f"Google Drive free space: {{free_gb:.1f}} GB")
if free_gb < {NEED8_GB}:
    raise SystemExit("Not enough Google Drive space (need {NEED8_GB} GB). In Drive, delete the checkpoints folders inside "
                     "MyDrive/ourvoice-training, ourvoice-training-r2 and ourvoice-training-r3 (keep ourvoice-lora) and "
                     "any .gguf file there, empty the Trash, then run this cell again.")
!mkdir -p {results} && echo "Saving to {keep}"
"""),
        md("## 3. Install the training tool (3–5 minutes)"),
        code("!pip install -q unsloth 2>&1 | tail -1"),
        md("## 4. Make the training data and check it in the 8B model's own format (about 2 minutes)\nThe last line must say **0 with problems**."),
        code(f"""
{here}
!python make_templates.py --per {per} | head -1
!python build_dataset.py | head -1
!python check_chat_format.py --base {BASE8} > check8.log 2>&1; tail -3 check8.log
"""),
        md(f"""
## 5. Train ({hours}), then build the model file (30–45 minutes)

Progress is saved to Google Drive every 10 steps. The first lines must show the 8B model loading and a step count
of about 64. If it stops with **out of memory**, send a screenshot.
"""),
        code(f"""
{here}
import glob, os, shutil
!nvidia-smi --query-gpu=memory.used,memory.total --format=csv
checked = os.path.exists("check8.log") and " 0 with problems" in open("check8.log").read()
if glob.glob("{keep}/*.gguf"):
    print("The model file is already in Google Drive: nothing to train or build. Go on to cell 6.")
elif not checked:
    print("Not trained: run cell 4 first; it must say '0 with problems'")
else:
    get_ipython().system("python finetune.py --base {BASE8} --batch 1 --accum 16 --train data/train.jsonl "
                         "--val data/val.jsonl --epochs {EPOCHS} --keep {keep} --out out8b")
    built = sorted(glob.glob("{KIT}/out8b*/**/*.gguf", recursive=True))
    free_gb = shutil.disk_usage("/content/drive/MyDrive").free / 1e9
    if built and free_gb > 6:
        shutil.copy(built[0], "{keep}/{name}.Q4_K_M.gguf")
        print("Model file also saved to Google Drive")
    elif built:
        print(f"Model file NOT copied to Drive ({{free_gb:.1f}} GB free, 6 needed): after a disconnect this cell rebuilds it.")
"""),
        md("## 6. Load the trained model into Ollama (about 5 minutes)\nMust end with **Answers directly**."),
        code(f"""
{here}
import os, shutil, subprocess, time, urllib.request, json
!apt-get -qq install -y zstd pciutils > /dev/null
!curl -fsSL https://ollama.com/install.sh | sh > /dev/null 2>&1 && echo "Ollama installed"
env = dict(os.environ, OLLAMA_CONTEXT_LENGTH="8192", OLLAMA_MAX_LOADED_MODELS="2")
subprocess.Popen(["ollama", "serve"], env=env, stdout=open("/content/ollama.log", "w"), stderr=subprocess.STDOUT)
for _ in range(60):
    try:
        urllib.request.urlopen("http://localhost:11434/api/tags", timeout=2)
        print("Ollama is running")
        break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit("Ollama did not start: see /content/ollama.log")
!ollama pull {CALLER} 2>&1 | tail -1
!ollama pull qwen3:4b-instruct 2>&1 | tail -1
{find_gguf}
if not gguf:
    raise SystemExit("No model file: run cell 5 first")
# The plain chat template of qwen3:4b-instruct (no thinking), exactly as the 8B was trained.
get_ipython().system(f'python make_modelfile.py "{{gguf}}" --base qwen3:4b-instruct --name {name} --create 2>&1 | tail -2')
body = {{"model": "{name}", "messages": [{{"role": "user", "content": "Say hello in five words."}}], "max_tokens": 60, "stream": False}}
req = urllib.request.Request("http://localhost:11434/v1/chat/completions", json.dumps(body).encode(), {{"Content-Type": "application/json"}})
text = json.load(urllib.request.urlopen(req, timeout=300))["choices"][0]["message"].get("content") or ""
print(repr(text[:150]))
print("Thinks first: the CRM removes it, but it costs time. Send a screenshot." if "<think>" in text else "Answers directly")
"""),
        md("## 7. Speed on the GPU (1 minute)"),
        code(f"""
{here}
!python -u speed_test.py --model {name}
!ollama ps
"""),
        md(f"""
## 8. The 60 test calls, with reruns (about 1 hour)

60 calls (the first 30 are the earlier test). A call whose simulated caller went off-script is rerun with a new
caller, up to 2 times; the end shows the strict score and the FAIR score. Every finished call is saved in Google
Drive (`{results}`): calls already there are not run again, and after a disconnect, rerun cells 1, 2, 6 and this
one and it carries on. Send a screenshot of the score at the end.
"""),
        code(f"""
{here}
!python -u evaluate.py --model {name} --out-dir {results} >> {results}/eval-{name}.log 2>&1; tail -22 {results}/eval-{name}.log
"""),
        md("## 9. Download the results\nRight-click **test-results-{name}.zip** in the Files panel → **Download** (small), and tell Claude."),
        code(f"""
!cd {results} && zip -q /content/test-results-{name}.zip eval-* && ls -l /content/test-results-{name}.zip
"""),
    ]
    cells += extra or []
    return {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": []},
                                         "kernelspec": {"display_name": "Python 3", "name": "python3"},
                                         "language_info": {"name": "python"}},
            "nbformat": 4, "nbformat_minor": 0}


# Round 4 (29 Oct): the trained 8B writes training calls against the test's simulated caller (generate.py). The
# scenarios at or below 70% fair in the 8B's 60-call test get three times the tries.
GEN = "/content/drive/MyDrive/ourvoice-r4-calls"
GEN_FILE = "generated-r4.jsonl"
FOCUS = "dispute,interested,faq_unknown,faq,wrong_person,are_you_human,confirm_attend,distress"
GEN_MINUTES = 150


def notebook_gen() -> dict:
    here = f"%cd {KIT}"
    cells = [
        md(f"""
# Round 4: our 8B writes training calls with the real simulated caller

No training tonight: the trained 8B from round 3 (its model file is in Google Drive, `{KEEP8}`) talks to the same
simulated caller as the test, with private trainer notes. Only calls that pass every check are kept, and never on
the two test scripts. About **2 h 45 min**: setup ~10 min, then the calls stop by themselves after
{GEN_MINUTES} minutes. **Every kept call is saved to Google Drive** (`{GEN}`) the moment it finishes.

Use the **college Google account** (the one whose Drive has `ourvoice-training-8b-r3`).

1. **Runtime → Change runtime type → T4 GPU**, then **Save**.
2. Upload **colab-kit.zip** in the Files panel on the left (folder icon).
3. Run the cells **in order, one at a time**. Keep this tab open; click in it now and then.

**After a disconnect:** reconnect, upload colab-kit.zip again and run cells 1–4: the calls already in Drive count,
and it carries on. You can also stop cell 4 at any time (■): nothing kept is lost.
"""),
        md("## 1. Check the GPU and unpack the kit"),
        code(f"""
!nvidia-smi --query-gpu=name,memory.total --format=csv
!unzip -o -q /content/colab-kit.zip -d /content/kit && echo "Kit unpacked"
{here}
!python -m unittest test_callkit test_sim test_generate test_fairness 2>&1 | tail -3
"""),
        md("## 2. Connect Google Drive\nColab asks you to allow access: choose the college account."),
        code(f"""
import glob, os
from google.colab import drive
drive.mount("/content/drive")
saved = sorted(glob.glob("{KEEP8}/*.gguf"))
if not saved:
    raise SystemExit("The 8B model file is not in this Drive ({KEEP8}). Is this the college account? "
                     "Runtime -> Disconnect and delete runtime, then run cell 2 again and choose that account.")
os.makedirs("{GEN}", exist_ok=True)
print("Model file:", saved[0])
print("Saving calls to {GEN}")
"""),
        md("## 3. Load the 8B into Ollama (about 5 minutes)\nMust end with **Answers directly**."),
        code(f"""
{here}
import glob, os, subprocess, time, urllib.request, json
!apt-get -qq install -y zstd pciutils > /dev/null
!curl -fsSL https://ollama.com/install.sh | sh > /dev/null 2>&1 && echo "Ollama installed"
env = dict(os.environ, OLLAMA_CONTEXT_LENGTH="8192", OLLAMA_MAX_LOADED_MODELS="2")
subprocess.Popen(["ollama", "serve"], env=env, stdout=open("/content/ollama.log", "w"), stderr=subprocess.STDOUT)
for _ in range(60):
    try:
        urllib.request.urlopen("http://localhost:11434/api/tags", timeout=2)
        print("Ollama is running")
        break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit("Ollama did not start: see /content/ollama.log")
!ollama pull {CALLER} 2>&1 | tail -1
!ollama pull qwen3:4b-instruct 2>&1 | tail -1
gguf = sorted(glob.glob("{KEEP8}/*.gguf"))[0]
# The plain chat template of qwen3:4b-instruct (no thinking), exactly as the 8B was trained and tested.
get_ipython().system(f'python make_modelfile.py "{{gguf}}" --base qwen3:4b-instruct --name {NAME8} --create 2>&1 | tail -2')
body = {{"model": "{NAME8}", "messages": [{{"role": "user", "content": "Say hello in five words."}}], "max_tokens": 60, "stream": False}}
req = urllib.request.Request("http://localhost:11434/v1/chat/completions", json.dumps(body).encode(), {{"Content-Type": "application/json"}})
text = json.load(urllib.request.urlopen(req, timeout=300))["choices"][0]["message"].get("content") or ""
print(repr(text[:150]))
print("Thinks first: send a screenshot." if "<think>" in text else "Answers directly")
"""),
        md(f"""
## 4. Write the calls ({GEN_MINUTES} minutes, stops by itself)

One line per call: **kept** or **rejected** with the reason. About half kept is normal. Leave it running; send a
screenshot at the end (or whenever you stop it).
"""),
        code(f"""
{here}
!python -u generate.py --teacher {NAME8} --caller {CALLER} --focus {FOCUS} --max-per-scenario 20 --count 400 --minutes {GEN_MINUTES} --out {GEN_FILE} --out-dir {GEN} 2>&1 | tee -a {GEN}/generate.log
"""),
        md("## 5. Pack the calls\nRight-click **r4-calls.zip** in the Files panel → **Download**, and tell Claude."),
        code(f"""
{here}
import collections, json
kept = [json.loads(l) for l in open("{GEN}/{GEN_FILE}", encoding="utf-8") if l.strip()]
print(len(kept), "calls kept:", dict(collections.Counter(r["meta"]["scenario"] for r in kept).most_common()))
!cd {GEN} && zip -q /content/r4-calls.zip {GEN_FILE} rejected.jsonl generate.log && ls -l /content/r4-calls.zip
"""),
    ]
    return {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": []},
                                         "kernelspec": {"display_name": "Python 3", "name": "python3"},
                                         "language_info": {"name": "python"}},
            "nbformat": 4, "nbformat_minor": 0}


# Round 4 training (30 Oct): fewer template variants (4 instead of 6) plus the 287 model-written calls x2, so the
# training fits one evening (~78 steps); a new Drive folder, so round 3's model file is kept.
KEEP8_R4 = "/content/drive/MyDrive/ourvoice-training-8b-r4"
NAME8_R4 = "ourvoice8b-r4"
PER_R4 = 4


def recheck_r3_cells() -> list:
    """Round 3's 8B, its test calls that today's CRM would answer differently (evaluate.crm_would_change: the
    single-name rule, 30 Oct), so round 3 and round 4 are compared under the same CRM. Optional, after cell 8."""
    return [
        md(f"""
## 10. Optional (about 15 minutes): round 3's calls under today's CRM

Round 3's test ran before the CRM asked for the full name instead of counting a first name alone as a failed try.
This loads round 3's model (`{KEEP8}`) and redoes **only the 8 calls** that rule changes, so the two rounds are
compared fairly. Then right-click **test-results-r3-recheck.zip** → **Download**.
"""),
        code(f"""
%cd {KIT}
import glob
gguf = sorted(glob.glob("{KEEP8}/*.gguf"))[0]
get_ipython().system(f'python make_modelfile.py "{{gguf}}" --base qwen3:4b-instruct --name {NAME8} --create 2>&1 | tail -2')
!python -u evaluate.py --model {NAME8} --out-dir {RESULTS8} >> {RESULTS8}/eval-{NAME8}.log 2>&1; tail -22 {RESULTS8}/eval-{NAME8}.log
!cd {RESULTS8} && zip -q /content/test-results-r3-recheck.zip eval-{NAME8}.* && ls -l /content/test-results-r3-recheck.zip
"""),
    ]


def notebook_retest() -> dict:
    """Round 4's model, nothing trained, under the 31 Oct CRM fixes: the same 60 calls, and 60 with new callers. Each
    seed has its own results folder, so no call from an older CRM is reused."""
    seeds = (2026, 7)
    results = {s: f"{KEEP8_R4}/results-crm4-seed{s}" for s in seeds}
    cells = [
        md(f"""
# Retest round 4's model under the CRM fixes

Nothing is trained, so this needs only about **40 minutes** of GPU. Two runs of 60 calls: **the same test as before**
(seed 2026) and **new callers** (seed 7). Every finished call is saved in Google Drive.

1. **Runtime → Change runtime type → T4 GPU**, then **Save**.
2. Upload the new **colab-kit.zip** in the Files panel on the left (folder icon). The old one will not work.
3. Run the cells **in order, one at a time**. Keep this tab open; click in it now and then.

**After a disconnect:** reconnect, upload colab-kit.zip again and run the cells from the top. Finished calls are not run again.
"""),
        md("## 1. Check the GPU and unpack the kit\nThe last line must say **OK**."),
        code(f"""
!nvidia-smi --query-gpu=name,memory.total --format=csv
!unzip -o -q /content/colab-kit.zip -d /content/kit && echo "Kit unpacked"
%cd {KIT}
!python -m unittest test_fairness test_callkit test_sim 2>&1 | tail -3
"""),
        md("## 2. Connect Google Drive\nColab asks you to allow access: choose the college account."),
        code(f"""
import glob
from google.colab import drive
drive.mount("/content/drive")
print("Model file:", glob.glob("{KEEP8_R4}/*.gguf") or "NOT FOUND: send a screenshot")
!mkdir -p {" ".join(results.values())} && echo "Results folders ready"
"""),
        md("## 3. Load round 4's model into Ollama (about 5 minutes)\nMust end with **Answers directly**."),
        code(f"""
%cd {KIT}
import glob, json, os, subprocess, time, urllib.request
!apt-get -qq install -y zstd pciutils > /dev/null
!curl -fsSL https://ollama.com/install.sh | sh > /dev/null 2>&1 && echo "Ollama installed"
env = dict(os.environ, OLLAMA_CONTEXT_LENGTH="8192", OLLAMA_MAX_LOADED_MODELS="2")
subprocess.Popen(["ollama", "serve"], env=env, stdout=open("/content/ollama.log", "w"), stderr=subprocess.STDOUT)
for _ in range(60):
    try:
        urllib.request.urlopen("http://localhost:11434/api/tags", timeout=2)
        print("Ollama is running")
        break
    except Exception:
        time.sleep(1)
else:
    raise SystemExit("Ollama did not start: see /content/ollama.log")
!ollama pull qwen2.5:7b 2>&1 | tail -1
!ollama pull qwen3:4b-instruct 2>&1 | tail -1
gguf = sorted(glob.glob("{KEEP8_R4}/*.gguf"))[0]
get_ipython().system(f'python make_modelfile.py "{{gguf}}" --base qwen3:4b-instruct --name {NAME8_R4} --create 2>&1 | tail -2')
body = {{"model": "{NAME8_R4}", "messages": [{{"role": "user", "content": "Say hello in five words."}}], "max_tokens": 60, "stream": False}}
req = urllib.request.Request("http://localhost:11434/v1/chat/completions", json.dumps(body).encode(), {{"Content-Type": "application/json"}})
text = json.load(urllib.request.urlopen(req, timeout=300))["choices"][0]["message"].get("content") or ""
print(repr(text[:150]))
print("Thinks first: send a screenshot." if "<think>" in text else "Answers directly")
"""),
    ]
    for n, (seed, title) in enumerate(((seeds[0], "The same 60 calls as before"), (seeds[1], "60 calls with new callers")), 4):
        log = f"{results[seed]}/eval-{NAME8_R4}.log"
        cells += [md(f"## {n}. {title} (about 15 minutes)\nThe spinner runs with nothing printed until the end. Send a screenshot of the score."),
                  code(f"""
%cd {KIT}
!python -u evaluate.py --model {NAME8_R4} --seed {seed} --out-dir {results[seed]} >> {log} 2>&1; tail -26 {log}
""")]
    cells += [md("## 6. Download the results\nRefresh the Files panel, right-click **retest-results.zip** → **Download**, and tell Claude."),
              code(f"""
!rm -f /content/retest-results.zip; cd {KEEP8_R4} && zip -q -r /content/retest-results.zip {" ".join(f"results-crm4-seed{s}" for s in seeds)} && ls -l /content/retest-results.zip
""")]
    return {"cells": cells, "metadata": {"accelerator": "GPU", "kernelspec": {"name": "python3", "display_name": "Python 3"}},
            "nbformat": 4, "nbformat_minor": 0}


def main() -> None:
    OUT.mkdir(exist_ok=True)
    zpath = OUT / "colab-kit.zip"
    files = kit_files()
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for src, name in files:
            z.write(src, name)
    for name, nb in (("train-on-colab.ipynb", notebook()), ("test-bigger-models.ipynb", test_notebook()),
                     ("train-8b-on-colab.ipynb", notebook_8b()), ("round4-calls-on-colab.ipynb", notebook_gen()),
                     ("train-8b-round4.ipynb", notebook_8b(KEEP8_R4, NAME8_R4, PER_R4, "Round 4 data (templates + the 287 calls "
                                                           "our 8B wrote with the real caller)", "~2 h 30 min", "4½ hours",
                                                           recheck_r3_cells())),
                     ("retest-crm-fixes.ipynb", notebook_retest())):
        (OUT / name).write_text(json.dumps(nb, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"Wrote {OUT / name}")
    print(f"Wrote {zpath} ({len(files)} files, {zpath.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
