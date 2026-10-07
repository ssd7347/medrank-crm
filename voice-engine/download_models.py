"""
Downloads the open models the speech engine uses, once:
  * the Piper voice (about 60 MB) from the official rhasspy/piper-voices repository on Hugging Face;
  * the Whisper model (about 140 MB for base.en), by loading it once through faster-whisper.

Usage:  python download_models.py [voice]     e.g. python download_models.py en_GB-alba-medium
"""

import sys
import urllib.request
from pathlib import Path

HERE = Path(__file__).resolve().parent
VOICES = HERE / "voices"
BASE = "https://huggingface.co/rhasspy/piper-voices/resolve/v1.0.0"


def piper_voice(name: str) -> None:
    # Names look like en_US-lessac-medium: language_REGION-speaker-quality.
    lang_region, speaker, quality = name.split("-")
    lang = lang_region.split("_")[0]
    VOICES.mkdir(exist_ok=True)
    for ext in (".onnx", ".onnx.json"):
        target = VOICES / f"{name}{ext}"
        if target.exists():
            print(f"have {target.name}")
            continue
        url = f"{BASE}/{lang}/{lang_region}/{speaker}/{quality}/{name}{ext}"
        print(f"downloading {url}")
        urllib.request.urlretrieve(url, target)


def whisper_model(name: str) -> None:
    from faster_whisper import WhisperModel

    print(f"loading Whisper {name} (downloads on first use)")
    WhisperModel(name, device="cpu", compute_type="int8")


if __name__ == "__main__":
    piper_voice(sys.argv[1] if len(sys.argv) > 1 else "en_US-lessac-medium")
    whisper_model("base.en")
    print("done")
