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


def kit_files() -> list[tuple[Path, str]]:
    files = [(p, f"training/{p.name}") for p in sorted(HERE.glob("*.py")) if not p.name.startswith("_")]
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


def notebook_8b() -> dict:
    here = f"%cd {KIT}"
    find_gguf = (f'import glob\nlocal = sorted(glob.glob("{KIT}/out8b*/**/*.gguf", recursive=True))\n'
                 f'saved = sorted(glob.glob("{KEEP8}/*.gguf"))\ngguf = (local or saved or [None])[0]\n'
                 f'print("Model file:", gguf)')
    cells = [
        md(f"""
# Train the 8B model and test it, all on Colab

Round {ROUND} data on **Qwen3-8B** (Apache 2.0), then the 60 test calls on the GPU. About **4 hours** in all:
training ~2 h, building the model file ~30–45 min, test ~1 h. It can be spread over several sessions:
**everything slow is saved to Google Drive** (`{KEEP8}`): training progress every 10 steps, the trained adapter,
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
!mkdir -p {RESULTS8} && echo "Saving to {KEEP8}"
"""),
        md("## 3. Install the training tool (3–5 minutes)"),
        code("!pip install -q unsloth 2>&1 | tail -1"),
        md("## 4. Make the training data and check it in the 8B model's own format (about 2 minutes)\nThe last line must say **0 with problems**."),
        code(f"""
{here}
!python make_templates.py --per {PER} | head -1
!python build_dataset.py | head -1
!python check_chat_format.py --base {BASE8} > check8.log 2>&1; tail -3 check8.log
"""),
        md(f"""
## 5. Train (about 2 hours), then build the model file (30–45 minutes)

Progress is saved to Google Drive every 10 steps. The first lines must show the 8B model loading and a step count
of about 64. If it stops with **out of memory**, send a screenshot.
"""),
        code(f"""
{here}
import glob, os, shutil
!nvidia-smi --query-gpu=memory.used,memory.total --format=csv
checked = os.path.exists("check8.log") and " 0 with problems" in open("check8.log").read()
if glob.glob("{KEEP8}/*.gguf"):
    print("The model file is already in Google Drive: nothing to train or build. Go on to cell 6.")
elif not checked:
    print("Not trained: run cell 4 first; it must say '0 with problems'")
else:
    get_ipython().system("python finetune.py --base {BASE8} --batch 1 --accum 16 --train data/train.jsonl "
                         "--val data/val.jsonl --epochs {EPOCHS} --keep {KEEP8} --out out8b")
    built = sorted(glob.glob("{KIT}/out8b*/**/*.gguf", recursive=True))
    free_gb = shutil.disk_usage("/content/drive/MyDrive").free / 1e9
    if built and free_gb > 6:
        shutil.copy(built[0], "{KEEP8}/{NAME8}.Q4_K_M.gguf")
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
get_ipython().system(f'python make_modelfile.py "{{gguf}}" --base qwen3:4b-instruct --name {NAME8} --create 2>&1 | tail -2')
body = {{"model": "{NAME8}", "messages": [{{"role": "user", "content": "Say hello in five words."}}], "max_tokens": 60, "stream": False}}
req = urllib.request.Request("http://localhost:11434/v1/chat/completions", json.dumps(body).encode(), {{"Content-Type": "application/json"}})
text = json.load(urllib.request.urlopen(req, timeout=300))["choices"][0]["message"].get("content") or ""
print(repr(text[:150]))
print("Thinks first: the CRM removes it, but it costs time. Send a screenshot." if "<think>" in text else "Answers directly")
"""),
        md("## 7. Speed on the GPU (1 minute)"),
        code(f"""
{here}
!python -u speed_test.py --model {NAME8}
!ollama ps
"""),
        md(f"""
## 8. The 60 test calls, with reruns (about 1 hour)

60 calls (the first 30 are the earlier test). A call whose simulated caller went off-script is rerun with a new
caller, up to 2 times; the end shows the strict score and the FAIR score. Every finished call is saved in Google
Drive (`{RESULTS8}`): calls already there are not run again, and after a disconnect, rerun cells 1, 2, 6 and this
one and it carries on. Send a screenshot of the score at the end.
"""),
        code(f"""
{here}
!python -u evaluate.py --model {NAME8} --out-dir {RESULTS8} >> {RESULTS8}/eval-{NAME8}.log 2>&1; tail -22 {RESULTS8}/eval-{NAME8}.log
"""),
        md("## 9. Download the results\nRight-click **test-results-8b.zip** in the Files panel → **Download** (small), and tell Claude."),
        code(f"""
!cd {RESULTS8} && zip -q /content/test-results-8b.zip eval-* && ls -l /content/test-results-8b.zip
"""),
    ]
    return {"cells": cells, "metadata": {"accelerator": "GPU", "colab": {"provenance": []},
                                         "kernelspec": {"display_name": "Python 3", "name": "python3"},
                                         "language_info": {"name": "python"}},
            "nbformat": 4, "nbformat_minor": 0}


def main() -> None:
    OUT.mkdir(exist_ok=True)
    zpath = OUT / "colab-kit.zip"
    files = kit_files()
    with zipfile.ZipFile(zpath, "w", zipfile.ZIP_DEFLATED) as z:
        for src, name in files:
            z.write(src, name)
    for name, nb in (("train-on-colab.ipynb", notebook()), ("test-bigger-models.ipynb", test_notebook()),
                     ("train-8b-on-colab.ipynb", notebook_8b())):
        (OUT / name).write_text(json.dumps(nb, indent=1, ensure_ascii=False), encoding="utf-8")
        print(f"Wrote {OUT / name}")
    print(f"Wrote {zpath} ({len(files)} files, {zpath.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
