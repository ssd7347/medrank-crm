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

PER = 6                     # variations of each script x scenario (x2 for weak ones): ~1,010 calls, ~64 steps, ~70 min
EPOCHS = 1
# Survives a disconnect. A new folder per round: training "carries on" from any checkpoint it finds, so reusing
# round 1's folder would skip round 2 entirely.
KEEP = "/content/drive/MyDrive/ourvoice-training-r2"
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

Round 2. Free Google Colab GPU, **about 1½ hours**. Run the cells **in order, from the top**, one at a time.

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
    raise SystemExit("Not enough Google Drive space (need {NEED_GB} GB). In Drive, delete MyDrive/ourvoice-training/checkpoints "
                     "and any .gguf file there, empty the Trash, then run this cell again.")
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
        md("""
## 6. Download the trained model

About 2.5 GB. This cell gives it a clear name; then download it from the **Files** panel on the left: open the
folder it names, right-click **ourvoice-r2.Q4_K_M.gguf** → **Download**, and keep this tab open until it finishes.
"""),
        code(f"""
import glob, os, shutil
found = [f for f in glob.glob("{KIT}/**/*.gguf", recursive=True) if not f.endswith("ourvoice-r2.Q4_K_M.gguf")]
if not found and not glob.glob("{KIT}/**/ourvoice-r2.Q4_K_M.gguf", recursive=True):
    print("No .gguf file found: send a screenshot of cell 5")
for f in found:
    target = os.path.join(os.path.dirname(f), "ourvoice-r2.Q4_K_M.gguf")
    shutil.move(f, target)
for f in glob.glob("{KIT}/**/ourvoice-r2.Q4_K_M.gguf", recursive=True):
    print(f"Ready to download ({{os.path.getsize(f) / 1e9:.1f}} GB):", f.replace("/content/", "Files panel → "))
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
