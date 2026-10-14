"""Packs everything Google Colab needs into two files in training/out/:

    python make_colab_bundle.py

* colab-kit.zip            the training kit, the industry packs and the calls already written on the laptop
* train-on-colab.ipynb     the notebook: tests, templates, model-written calls with a 14B teacher, dataset, training,
                           and saving the trained model to Google Drive

Upload the notebook to Colab (File -> Upload notebook), then upload colab-kit.zip in its Files panel, and run the
cells from the top. See README.md, "Train".
"""

import json
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
OUT = HERE / "out"

TEACHER = "qwen2.5:14b"          # Apache 2.0, ~9 GB: fits the free T4's 15 GB with room for its working memory
GENERATE_MINUTES = 80            # leaves time to train within a free Colab session
PARALLEL = 2                     # two calls written at once: the GPU has room, and it nearly doubles the output


def kit_files() -> list[tuple[Path, str]]:
    files = [(p, f"training/{p.name}") for p in sorted(HERE.glob("*.py")) if not p.name.startswith("_")]
    files += [(HERE / "README.md", "training/README.md"), (HERE / "spec" / "agent-spec.json", "training/spec/agent-spec.json")]
    packs = REPO / "backend" / "src" / "main" / "resources" / "packs"
    files += [(p, f"backend/src/main/resources/packs/{p.name}") for p in sorted(packs.iterdir()) if p.is_file()]
    laptop = HERE / "data" / "generated.jsonl"
    if laptop.exists():
        files.append((laptop, "training/data/generated_laptop.jsonl"))
    return files


def md(text: str) -> dict:
    return {"cell_type": "markdown", "metadata": {}, "source": text.strip().splitlines(keepends=True)}


def code(text: str) -> dict:
    return {"cell_type": "code", "metadata": {}, "execution_count": None, "outputs": [],
            "source": text.strip().splitlines(keepends=True)}


def notebook() -> dict:
    gen = " & ".join(f"python -u generate.py --teacher {TEACHER} --count 400 --minutes {GENERATE_MINUTES} "
                     f"--out generated_colab{i}.jsonl --seed {i} > gen{i}.log 2>&1" for i in range(1, PARALLEL + 1)) + " & wait"
    cells = [
        md(f"""
# Train our own voice-agent model

Free Google Colab GPU, about 2½ hours in all. Run the cells **in order, from the top**; each one says what it does.

1. **Runtime → Change runtime type → T4 GPU**, then **Save**.
2. Upload **colab-kit.zip** in the Files panel on the left (folder icon), into `/content`.
3. Run each cell with ▶ and wait for it to finish before the next.

Everything here uses made-up people only. Models: teacher `{TEACHER}` and base `Qwen3-4B-Instruct-2507`,
both Apache 2.0 (commercial use allowed).
"""),
        md("## 1. Check the GPU and unpack the kit"),
        code("""
!nvidia-smi --query-gpu=name,memory.total --format=csv
!unzip -o -q /content/colab-kit.zip -d /content/kit && echo "Kit unpacked"
%cd /content/kit/training
!python -m unittest test_callkit test_sim 2>&1 | tail -3
"""),
        md("## 2. Start Ollama with the teacher model (about 5 minutes)"),
        code(f"""
!apt-get -qq install -y zstd pciutils > /dev/null
!curl -fsSL https://ollama.com/install.sh | sh > /dev/null 2>&1 && echo "Ollama installed"
import os, subprocess, time, urllib.request
env = dict(os.environ, OLLAMA_CONTEXT_LENGTH="8192", OLLAMA_NUM_PARALLEL="{PARALLEL}")
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
!ollama pull {TEACHER} 2>&1 | tail -1
"""),
        md("## 3. Template calls (seconds)"),
        code("!python make_templates.py --per 12 | head -1"),
        md(f"""
## 4. Model-written calls ({GENERATE_MINUTES} minutes)

The teacher plays the agent and another copy plays the caller; only calls that pass every check are kept.
This cell shows progress every 2 minutes and finishes by itself.
"""),
        code(f"""
import glob, subprocess, time
proc = subprocess.Popen("{gen}", shell=True)
start = time.time()
while proc.poll() is None:
    time.sleep(120)
    kept = sum(sum(1 for _ in open(f)) for f in glob.glob("data/generated_colab*.jsonl"))
    print(f"{{(time.time() - start) / 60:.0f}} min: {{kept}} good calls so far", flush=True)
time.sleep(5)
!tail -n 2 gen*.log
"""),
        md("Keep a copy of the new calls on your computer (in case the session ends early)."),
        code("""
!cat data/generated_colab*.jsonl > /content/generated_colab.jsonl && wc -l /content/generated_colab.jsonl
from google.colab import files
files.download("/content/generated_colab.jsonl")
"""),
        md("## 5. Build the training data"),
        code("!python build_dataset.py"),
        md("## 6. Train (about 45–75 minutes)\nOllama is stopped first so the GPU is free for training."),
        code("""
!pkill -x ollama; sleep 3; nvidia-smi --query-gpu=memory.used --format=csv
!pip install -q unsloth 2>&1 | tail -1
!python finetune.py --train data/train.jsonl --val data/val.jsonl
"""),
        md("""
## 7. Save the trained model to Google Drive

The file is about 2.5 GB, too big to download reliably from the browser. This copies it to your Google Drive
(Colab asks you to allow access); download it from Drive to the laptop afterwards.
"""),
        code("""
import glob, shutil
from google.colab import drive
drive.mount("/content/drive")
found = glob.glob("/content/kit/training/**/*.gguf", recursive=True)
print(found)
for f in found:
    shutil.copy(f, "/content/drive/MyDrive/")
    print("Copied to Google Drive:", f.split("/")[-1])
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
    nb = OUT / "train-on-colab.ipynb"
    nb.write_text(json.dumps(notebook(), indent=1, ensure_ascii=False), encoding="utf-8")
    print(f"Wrote {zpath} ({len(files)} files, {zpath.stat().st_size // 1024} KB)")
    print(f"Wrote {nb}")


if __name__ == "__main__":
    main()
