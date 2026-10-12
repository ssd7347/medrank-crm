"""Loads the trained model (the .gguf file from finetune.py) into Ollama as "ourvoice".

    python make_modelfile.py C:\\Downloads\\ourvoice-Q4_K_M.gguf --create

The chat template (how messages and tool calls are laid out) is copied from the base model in Ollama, so the
trained model reads conversations exactly as it saw them in training.
"""

import argparse
import re
import subprocess
from pathlib import Path


def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("gguf", help="the .gguf file downloaded from Colab")
    ap.add_argument("--base", default="qwen3:4b-instruct", help="the same base model, as installed in Ollama")
    ap.add_argument("--name", default="ourvoice")
    ap.add_argument("--create", action="store_true", help="also run 'ollama create'")
    a = ap.parse_args()

    gguf = Path(a.gguf).resolve()
    if not gguf.exists():
        raise SystemExit(f"{gguf} does not exist")
    base = subprocess.run(["ollama", "show", a.base, "--modelfile"], capture_output=True, text=True, encoding="utf-8")
    if base.returncode != 0:
        raise SystemExit(f"Could not read {a.base} from Ollama (is it installed? ollama pull {a.base}):\n{base.stderr}")
    text = base.stdout
    template = re.search(r'^TEMPLATE """(.*?)"""', text, re.S | re.M)
    if not template:
        raise SystemExit(f"{a.base} has no chat template in Ollama")
    params = [p for p in re.findall(r"^PARAMETER .*$", text, re.M) if not re.match(r"PARAMETER (temperature|num_ctx)", p)]
    licence = re.search(r'^LICENSE """(.*?)"""', text, re.S | re.M)

    out = [f"FROM {gguf}", f'TEMPLATE """{template.group(1)}"""', *params,
           # Phone replies: steady and short.
           "PARAMETER temperature 0.3", "PARAMETER num_ctx 8192"]
    if licence:
        out.append(f'LICENSE """{licence.group(1)}"""')
    modelfile = Path(__file__).resolve().parent / "out" / "Modelfile"
    modelfile.parent.mkdir(exist_ok=True)
    modelfile.write_text("\n".join(dict.fromkeys(out)) + "\n", encoding="utf-8")
    print(f"Wrote {modelfile}")
    if a.create:
        subprocess.run(["ollama", "create", a.name, "-f", str(modelfile)], check=True)
        print(f"\nDone. Test it:  python evaluate.py --model {a.name}\n"
              f"Use it in the CRM: set LOCAL_LLM_MODEL={a.name} in backend/.env and restart the backend.")
    else:
        print(f"Next:  ollama create {a.name} -f {modelfile}")


if __name__ == "__main__":
    main()
