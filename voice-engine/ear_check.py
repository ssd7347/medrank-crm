"""
How well do the agent's ears (Whisper) hear real callers on a phone line? A check to run before paying for one.

Every recording is heard three ways:
  plain   straight to Whisper, as from a good microphone: the speaker and the model alone.
  line    as a real call reaches the engine (phone.py): 8 kHz, mu-law (G.711) both ways, cut into utterances by the
          same end-of-speech rule, each utterance back to 16 kHz and to Whisper with the live call's settings
          (engine.stt_pcm).
  mobile  as 'line', but first through AMR-NB at 12.2 kbit/s, the codec on a mobile phone's radio link.
For each: words wrong (word error rate, after writing numbers, "Rs." and the like the same way on both sides), the
key words the CRM must hear exactly (names, IDs, times), and the time Whisper took on this computer.

Usage (from voice-engine/, with the .venv's python):
  python ear_check.py --piper recordings/piper-lessac     a baseline "speaker" made from Piper's own voice
  python ear_check.py [recordings] [--models base.en small.en] [--paths plain line mobile]

Recordings: recordings/<speaker>/01.m4a, 02.m4a ... one per sentence of ear_sentences.txt, in any format FFmpeg
reads (phone recorders' .m4a, .wav, .mp3, .ogg); or a single recordings/<speaker>/all.m4a reading every sentence in
order with a 2-second pause between them. Writes recordings/ear-report.md and recordings/ear-report.json.
Recordings are people's voices: they stay on this computer (recordings/ is in .gitignore).

What this cannot copy: the caller's own phone (noise suppression, its microphone), VoLTE's wider-band codecs, and
whatever the networks and Plivo do between them. The first real call is still the real test.
"""

from __future__ import annotations

import argparse
import io
import json
import re
import statistics
import sys
import time
from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path

import numpy as np

import phone
from phone import RATE, STT_RATE

HERE = Path(__file__).resolve().parent
PATHS = ("plain", "line", "mobile")

# ---------------------------------------------------------------------------------------------- sentences


@dataclass
class Sentence:
    text: str
    keys: list[str] = field(default_factory=list)
    name: str = ""              # the name the CRM would expect on this call (the hint)
    decoy: str = ""             # a similar other person's name


def load_sentences(path: Path) -> list[Sentence]:
    """One sentence per line, `text | key; key | name / decoy`. Lines starting with # and blank lines do not
    count."""
    out = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        text, _, rest = line.partition("|")
        keys, _, names = rest.partition("|")
        name, _, decoy = names.partition("/")
        out.append(Sentence(text.strip(), [k.strip() for k in keys.split(";") if k.strip()], name.strip(),
                            decoy.strip()))
    return out


# ---------------------------------------------------------------------------------------------- scoring

UNITS = {w: i for i, w in enumerate("zero one two three four five six seven eight nine".split())}
TEENS = {w: i + 10 for i, w in enumerate(
    "ten eleven twelve thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split())}
TENS = {w: (i + 2) * 10 for i, w in enumerate("twenty thirty forty fifty sixty seventy eighty ninety".split())}
SCALES = {"thousand": 1000, "lakh": 100000, "lakhs": 100000, "crore": 10000000, "crores": 10000000}
ORDINALS = {"first": "one", "second": "two", "third": "three", "fourth": "four", "fifth": "five", "sixth": "six",
            "seventh": "seven", "eighth": "eight", "ninth": "nine", "tenth": "ten", "eleventh": "eleven",
            "twelfth": "twelve", "twentieth": "twenty", "thirtieth": "thirty",
            **{w + "th": w for w in "thirteen fourteen fifteen sixteen seventeen eighteen nineteen".split()}}
SAME = {"okay": "ok", "checkup": "check up", "alright": "all right", "id": "i d"}


def _digit(word: str) -> str | None:
    if len(word) == 1 and word.isdigit():
        return word
    return str(UNITS[word]) if word in UNITS else None


def _number(words: list[str], i: int) -> tuple[int, int]:
    """Reads number words from words[i]: "two thousand five hundred" is 2500. Digits read one by one ("four five")
    and years ("nineteen ninety two") are separate numbers. Returns (value, words used); (0, 0) if none."""
    total, cur, last, j = 0, None, None, i
    while j < len(words):
        w = words[j]
        if w in UNITS:
            if last in ("unit", "teen"):
                break
            cur, last = (cur or 0) + UNITS[w], "unit"
        elif w in TEENS or w in TENS:
            if last in ("unit", "teen", "tens"):
                break
            cur, last = (cur or 0) + (TEENS.get(w) or TENS[w]), "teen" if w in TEENS else "tens"
        elif w == "hundred":
            if cur is None or last == "hundred":
                break
            cur, last = cur * 100, "hundred"
        elif w in SCALES:
            if cur is None:
                break
            total, cur, last = total + cur * SCALES[w], None, "scale"
        else:
            break
        j += 1
    return (total + (cur or 0), j - i) if j > i else (0, 0)


def normalise(text: str) -> list[str]:
    """Words to compare, written one way whatever Whisper chose: numbers as single digits ("4512", "four five one
    two" and "45 12" are all 4 5 1 2; "5,000" and "five thousand" are 5 0 0 0), "Rs. 500" and "₹500" as "500
    rupees", "AC", "A.C." and "a c" as "a c", "p.m." as "pm", no punctuation or capitals."""
    t = re.sub(r"\bOK\b", "ok", text)
    t = re.sub(r"(?<![A-Za-z])[A-Z]{2,4}(?![a-z])", lambda m: " ".join(m.group(0)), t)  # AC, EMI, ID, PM
    t = t.lower().replace("’", "'")
    t = re.sub(r"(?<=\d),(?=\d\d)", "", t)                                 # 5,000 and 1,00,000
    t = re.sub(r"(?:₹|\brs\b\.?)\s*(\d+)", r"\1 rupees", t)
    t = re.sub(r"(\d)(?:st|nd|rd|th)\b", r"\1", t)
    t = re.sub(r"(?<=\d)(?=[a-z])|(?<=[a-z])(?=\d)", " ", t)
    t = re.sub(r"[^a-z0-9 ]+", " ", t.replace("'", ""))
    words = " ".join(SAME.get(w, w) for w in (ORDINALS.get(w, w) for w in t.split())).split()
    out: list[str] = []
    i = 0
    while i < len(words):
        w = words[i]
        if w in ("double", "triple") and i + 1 < len(words) and _digit(words[i + 1]) is not None:
            out += [_digit(words[i + 1])] * (2 if w == "double" else 3)
            i += 2
            continue
        if w.isdigit():
            out += list(w)
            i += 1
            continue
        value, used = _number(words, i)
        if used:
            out += list(str(value))
            i += used
        else:
            out.append(w)
            i += 1
    merged: list[str] = []
    for w in out:                                                            # "p m" -> "pm"
        if w == "m" and merged and merged[-1] in ("a", "p") and len(merged) > 1 and merged[-2].isdigit():
            merged[-1] += "m"
        else:
            merged.append(w)
    return merged


def edits(ref: list[str], hyp: list[str]) -> int:
    """Words substituted, missed or added (Levenshtein distance over words)."""
    row = list(range(len(hyp) + 1))
    for i, r in enumerate(ref, 1):
        prev, row[0] = row[0], i
        for j, h in enumerate(hyp, 1):
            prev, row[j] = row[j], min(row[j] + 1, row[j - 1] + 1, prev + (r != h))
    return row[-1]


def heard_key(key: str, hyp: list[str]) -> bool:
    k = normalise(key)
    return any(hyp[i:i + len(k)] == k for i in range(len(hyp) - len(k) + 1)) if k else True


# ---------------------------------------------------------------------------------------------- the phone line


def amr(pcm8: np.ndarray, bit_rate: int = 12200) -> np.ndarray:
    """8 kHz int16 through an AMR-NB encoder and decoder (a mobile phone's radio link)."""
    import av

    buf = io.BytesIO()
    out = av.open(buf, "w", format="amr")
    stream = out.add_stream("libopencore_amrnb", rate=RATE)
    stream.bit_rate, stream.layout = bit_rate, "mono"
    frame = av.AudioFrame.from_ndarray(pcm8.astype(np.int16).reshape(1, -1), format="s16", layout="mono")
    frame.sample_rate = RATE
    for packet in [*stream.encode(frame), *stream.encode(None)]:
        out.mux(packet)
    out.close()
    with av.open(io.BytesIO(buf.getvalue()), "r", format="amr") as inp:
        y = np.concatenate([f.to_ndarray().reshape(-1) for f in inp.decode(audio=0)])
    return np.clip(y * 32768.0, -32768, 32767).astype(np.int16)


def line_audio(x16: np.ndarray, mobile: bool = False) -> np.ndarray:
    """16 kHz float audio (-1..1) -> the 8 kHz int16 samples the engine decodes from Plivo's mu-law stream."""
    pcm8 = np.clip(phone.resample(x16 * 32768.0, STT_RATE, RATE), -32768, 32767).astype(np.int16)
    if mobile:
        pcm8 = amr(pcm8)
    return phone.ulaw_decode(phone.ulaw_encode(pcm8))


def utterances(line8: np.ndarray) -> list[np.ndarray]:
    """Cuts the line audio exactly as a call does (phone.Utterances), with quiet line noise before and after."""
    noise = np.random.default_rng(0).normal(0, 20, int(1.5 * RATE)).astype(np.int16)
    audio = np.concatenate([noise[:RATE // 2], line8, noise[RATE // 2:]])
    ears, pieces = phone.Utterances(), []
    for i in range(0, len(audio), phone.FRAME):
        pieces += ears.feed(audio[i:i + phone.FRAME])
    return pieces


def hear(stt, x16: np.ndarray, path: str, hint: str = "") -> tuple[str, int, int]:
    """(text, utterances, milliseconds in Whisper). ``stt`` takes 16 kHz float audio, like engine.stt_pcm, and with
    ``hint`` the name to expect as a second argument."""
    if path == "plain":
        pieces = [x16.astype(np.float32)]
    else:
        pieces = [phone.resample(u, RATE, STT_RATE) / 32768.0 for u in utterances(line_audio(x16, path == "mobile"))]
    t0 = time.perf_counter()
    text = " ".join(t for t in ((stt(p, hint) if hint else stt(p)).strip() for p in pieces) if t)
    return text, len(pieces), int((time.perf_counter() - t0) * 1000)


# ---------------------------------------------------------------------------------------------- recordings


def reading_spans(x16: np.ndarray, gap_s: float = 1.0) -> list[tuple[int, int]]:
    """One long reading -> (start, end) sample positions of each stretch of speech between pauses of at least
    ``gap_s`` seconds, with 0.2 s of margin."""
    n = 320                                                                  # 20 ms frames
    frames = len(x16) // n
    if frames == 0:
        return []
    rms = np.sqrt(np.mean(x16[:frames * n].reshape(frames, n).astype(np.float64) ** 2, axis=1))
    db = 20 * np.log10(rms + 1e-9)
    floor, top = np.percentile(db, 10), np.percentile(db, 90)
    loud = db > floor + 0.35 * (top - floor)
    spans, start, quiet = [], None, 0
    for i, is_loud in enumerate(list(loud) + [False] * int(gap_s * 50 + 1)):
        if is_loud:
            start = i if start is None else start
            quiet = 0
        elif start is not None:
            quiet += 1
            if quiet >= gap_s * 50:
                end = i - quiet + 1
                if np.sum(loud[start:end]) >= 15:                            # 0.3 s of speech, not a click
                    spans.append((max(0, (start - 10) * n), min(len(x16), (end + 10) * n)))
                start, quiet = None, 0
    return spans


def split_reading(x16: np.ndarray, gap_s: float = 1.0) -> list[np.ndarray]:
    """One long reading -> one piece per stretch of speech, cut at pauses of at least ``gap_s`` seconds."""
    return [x16[a:b] for a, b in reading_spans(x16, gap_s)]


def group_pieces(texts: list[str], sentences: list[Sentence], most: int = 3) -> list[list[int]] | None:
    """More pieces than sentences (the reader paused inside a sentence, or coughed): which neighbouring pieces make up
    each sentence, chosen so that what was heard matches the sentences best. Each sentence gets 1 to ``most`` pieces
    in order. None if the pieces cannot cover the sentences."""
    m, k = len(texts), len(sentences)
    if not k <= m <= most * k:
        return None
    refs = [normalise(s.text) for s in sentences]
    inf = float("inf")
    cost = [[inf] * (k + 1) for _ in range(m + 1)]
    back = [[0] * (k + 1) for _ in range(m + 1)]
    cost[0][0] = 0
    for i in range(1, m + 1):
        for j in range(1, k + 1):
            for take in range(1, min(most, i) + 1):
                before = cost[i - take][j - 1]
                if before == inf:
                    continue
                c = before + edits(refs[j - 1], normalise(" ".join(texts[i - take:i])))
                if c < cost[i][j]:
                    cost[i][j], back[i][j] = c, take
    if cost[m][k] == inf:
        return None
    groups, i = [], m
    for j in range(k, 0, -1):
        take = back[i][j]
        groups.append(list(range(i - take, i)))
        i -= take
    return groups[::-1]


def load_audio(path: Path) -> np.ndarray:
    from faster_whisper.audio import decode_audio

    return decode_audio(str(path), sampling_rate=STT_RATE)


def find_recordings(folder: Path, sentences: list[Sentence], align=None) -> tuple[dict[str, dict[int, np.ndarray]],
                                                                                 list[str]]:
    """{speaker: {sentence number: 16 kHz audio}} and problems found. ``align`` (16 kHz audio -> text) is used only
    when a whole reading has more pieces than sentences, to find which pieces belong together."""
    count = len(sentences)
    speakers, problems = {}, []
    for d in sorted(p for p in folder.iterdir() if p.is_dir()):
        files = [f for f in sorted(d.iterdir()) if f.is_file()]
        whole = [f for f in files if f.stem.lower() == "all"]
        if whole:
            audio = load_audio(whole[0])
            spans = reading_spans(audio)
            groups = [[i] for i in range(len(spans))] if len(spans) == count else None
            if groups is None and len(spans) > count and align is not None:
                groups = group_pieces([align(audio[a:b]) for a, b in spans], sentences)
                if groups is not None:
                    joined = [f"sentence {j + 1} = pieces {'+'.join(str(i + 1) for i in g)}"
                              for j, g in enumerate(groups) if len(g) > 1]
                    problems.append(f"{d.name}: {whole[0].name} had {len(spans)} pieces by its pauses, not {count}; "
                                    f"joined by matching them to the sentences: {', '.join(joined)}")
            if groups is None:
                problems.append(f"{d.name}: {whole[0].name} has {len(spans)} sentences by its pauses, not {count}; "
                                "record it again with a 2-second pause between sentences, or one file per sentence")
                continue
            speakers[d.name] = {j + 1: audio[spans[g[0]][0]:spans[g[-1]][1]] for j, g in enumerate(groups)}
            continue
        found = {}
        for f in files:
            if f.stem.isdigit() and 1 <= int(f.stem) <= count:
                found[int(f.stem)] = load_audio(f)
        if not found:
            problems.append(f"{d.name}: no recordings named 01, 02 ... or all")
            continue
        missing = [i for i in range(1, count + 1) if i not in found]
        if missing:
            problems.append(f"{d.name}: no recording for sentence(s) {', '.join(map(str, missing))} (left out)")
        speakers[d.name] = found
    return speakers, problems


def make_piper(out: Path, sentences: list[Sentence], voice_path: str | None) -> None:
    import engine

    if voice_path:
        engine.PIPER_VOICE = voice_path
    out.mkdir(parents=True, exist_ok=True)
    for i, s in enumerate(sentences, 1):
        (out / f"{i:02d}.wav").write_bytes(engine.speak_wav(s.text))
    print(f"wrote {len(sentences)} recordings to {out}")


# ---------------------------------------------------------------------------------------------- the check


CONTACT = "Ramesh Kumar"        # the hint for sentences without a name: a call always has one contact's name


def hint_for(s: Sentence, mode: str) -> str:
    """none: no hint. right: the name the CRM expects (on a real outbound call, the contact's). decoy: a similar
    other person's name, as when someone else answers the contact's phone."""
    if mode == "none":
        return ""
    return (s.name if mode == "right" else s.decoy) or CONTACT


def decoy_heard(s: Sentence, hyp: list[str]) -> bool:
    """Did the transcript take a word of the decoy's name that the speaker did not say (Rajesh for Ramesh)?"""
    said = set(normalise(s.text)) | set(normalise(s.name))
    return any(w in hyp and w not in said for w in normalise(s.decoy))


def check(speakers, sentences, models: dict, paths, hints=("none",)) -> list[dict]:
    results = []
    for speaker, audio in speakers.items():
        for name, stt in models.items():
            for path in paths:
                for mode in hints:
                    items = []
                    for i, x16 in sorted(audio.items()):
                        s = sentences[i - 1]
                        text, pieces, ms = hear(stt, x16, path, hint_for(s, mode))
                        ref, hyp = normalise(s.text), normalise(text)
                        items.append({"n": i, "said": s.text, "heard": text, "words": len(ref),
                                      "errors": edits(ref, hyp), "keys": len(s.keys),
                                      "keys_missed": [k for k in s.keys if not heard_key(k, hyp)],
                                      "decoy_heard": mode == "decoy" and bool(s.decoy) and decoy_heard(s, hyp),
                                      "pieces": pieces, "ms": ms})
                    words = sum(it["words"] for it in items)
                    errors = sum(it["errors"] for it in items)
                    keys = sum(it["keys"] for it in items)
                    results.append({"speaker": speaker, "model": name, "path": path, "hint": mode,
                                    "sentences": len(items), "words": words, "errors": errors,
                                    "wer": errors / words if words else 0.0, "keys": keys,
                                    "keys_heard": keys - sum(len(it["keys_missed"]) for it in items),
                                    "decoys": sum(1 for i in audio if sentences[i - 1].decoy) if mode == "decoy" else 0,
                                    "decoys_heard": sum(it["decoy_heard"] for it in items),
                                    "median_ms": int(statistics.median(it["ms"] for it in items)) if items else 0,
                                    "items": items})
                    r = results[-1]
                    decoys = f"  decoy heard {r['decoys_heard']}/{r['decoys']}" if mode == "decoy" else ""
                    print(f"{speaker:>16} {name:>9} {path:>6} {mode:>5}: words wrong {r['wer']:6.1%}  "
                          f"keys {r['keys_heard']}/{r['keys']}{decoys}  {r['median_ms']} ms", flush=True)
    return results


def report(results: list[dict], problems: list[str], levels: dict[str, float]) -> str:
    out = [f"# Ear check, {datetime.now():%d %b %Y %H:%M}", "",
           "plain = straight to Whisper; line = Plivo's 8 kHz mu-law line and the call's end-of-speech cutting, as "
           "phone.py hears it; mobile = the same after a mobile phone's AMR-NB codec. Time = Whisper on this "
           "computer per sentence (median). Hint: none; right = Whisper told the name the CRM expects; decoy = told "
           "a similar other person's name (decoy heard = the transcript took that other name).", "",
           "| Speaker | Ear | Path | Hint | Words wrong | Key words heard | Decoy heard | Time |",
           "|---|---|---|---|---|---|---|---|"]
    for r in results:
        decoys = f"{r['decoys_heard']}/{r['decoys']}" if r.get("hint") == "decoy" else ""
        out.append(f"| {r['speaker']} | {r['model']} | {r['path']} | {r.get('hint', 'none')} | {r['wer']:.1%} "
                   f"({r['errors']}/{r['words']}) | {r['keys_heard']}/{r['keys']} | {decoys} | {r['median_ms']} ms |")
    if problems:
        out += ["", "## Problems with the recordings", ""] + [f"- {p}" for p in problems]
    if levels:
        out += ["", "Loudest point of each speaker's recordings (median): " +
                ", ".join(f"{s} {round(db)} dBFS" for s, db in levels.items()) +
                ". Much below -20 dBFS and the call's end-of-speech rule may miss quiet words."]
    for r in results:
        bad = [it for it in r["items"] if it["errors"] or it["keys_missed"] or it["pieces"] != 1
               or it.get("decoy_heard")]
        if not bad:
            continue
        out += ["", f"## {r['speaker']}, {r['model']}, {r['path']}, hint {r.get('hint', 'none')}", ""]
        for it in bad:
            notes = []
            if it.get("decoy_heard"):
                notes.append("DECOY HEARD")
            if it["keys_missed"]:
                notes.append("missed: " + ", ".join(it["keys_missed"]))
            if it["pieces"] == 0:
                notes.append("the call heard nothing")
            elif it["pieces"] > 1:
                notes.append(f"cut into {it['pieces']} turns")
            out.append(f"- {it['n']:02d} said \"{it['said']}\" heard \"{it['heard']}\"" +
                       (f" ({'; '.join(notes)})" if notes else ""))
    return "\n".join(out) + "\n"


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description=__doc__.split("\n\n")[0])
    ap.add_argument("recordings", nargs="?", default=str(HERE / "recordings"))
    ap.add_argument("--sentences", default=str(HERE / "ear_sentences.txt"))
    ap.add_argument("--models", nargs="+", default=["base.en", "small.en"])
    ap.add_argument("--paths", nargs="+", default=list(PATHS), choices=PATHS)
    ap.add_argument("--hints", nargs="+", default=["none"], choices=("none", "right", "decoy"),
                    help="also hear each sentence with the expected name (right) or a similar other name (decoy) "
                         "given to Whisper")
    ap.add_argument("--piper", metavar="FOLDER", help="write Piper's reading of every sentence to FOLDER, then stop")
    ap.add_argument("--voice", help="the Piper .onnx voice for --piper (default: the engine's)")
    args = ap.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    sentences = load_sentences(Path(args.sentences))
    if args.piper:
        make_piper(Path(args.piper), sentences, args.voice)
        return 0
    folder = Path(args.recordings)
    if not folder.is_dir():
        print(f"no folder {folder}: put recordings in {folder}/<speaker>/01.m4a ...", file=sys.stderr)
        return 2
    import engine
    from faster_whisper import WhisperModel

    models = {}
    for name in args.models:
        model = WhisperModel(name, device="cpu", compute_type="int8", cpu_threads=engine.CPU_THREADS)
        models[name] = lambda audio, hint="", m=model: engine.stt_pcm(audio, m, hint)
    align = models.get("small.en") or next(iter(models.values()))
    speakers, problems = find_recordings(folder, sentences, align)
    for p in problems:
        print("note:", p)
    if not speakers:
        return 2
    levels = {s: statistics.median(20 * np.log10(np.max(np.abs(x)) + 1e-9) for x in a.values())
              for s, a in speakers.items()}
    results = check(speakers, sentences, models, args.paths, args.hints)
    text = report(results, problems, levels)
    (folder / "ear-report.md").write_text(text, encoding="utf-8")
    (folder / "ear-report.json").write_text(json.dumps(results, indent=1), encoding="utf-8")
    print(f"\nreport: {folder / 'ear-report.md'}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
