"""Tests for ear_check.py with no Whisper model: the scoring, the simulated phone line, cutting readings into
sentences, and the whole check with a fake ear on made-up audio.

    .venv\\Scripts\\python -m unittest test_ear_check
"""

import json
import tempfile
import unittest
import warnings
import wave
from pathlib import Path

import numpy as np

import ear_check as ec

SR = ec.STT_RATE


def burst(seconds, hz=300.0, level=0.25):
    """A tone loud enough to count as speech, as 16 kHz float audio."""
    t = np.arange(int(seconds * SR)) / SR
    return (level * np.sin(2 * np.pi * hz * t)).astype(np.float32)


def hush(seconds, level=0.0005, seed=3):
    return np.random.default_rng(seed).normal(0, level, int(seconds * SR)).astype(np.float32)


def write_wav(path, x16):
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes((np.clip(x16, -1, 1) * 32767).astype(np.int16).tobytes())


class ScoringTest(unittest.TestCase):
    def same(self, a, b):
        self.assertEqual(ec.normalise(a), ec.normalise(b), f"{a!r} vs {b!r}")

    def test_numbers_are_written_one_way(self):
        self.same("My customer ID is AC four five one two.", "my customer id is A.C. 45 12")
        self.same("AC four five one two", "AC4512")
        self.same("fifteenth August nineteen ninety two", "15th August 1992")
        self.same("five thousand rupees", "Rs. 5,000")
        self.same("five thousand rupees", "₹5,000")
        self.same("one lakh twenty thousand", "1,20,000")
        self.same("two thousand five hundred", "2500")
        self.same("nine eight four zero one double two three five six", "98401 22356")
        self.same("triple seven", "777")
        self.same("after six PM", "after 6 p.m.")
        self.same("after six PM", "after 6pm")
        self.same("on the tenth", "on the 10th")
        self.same("Don't call", "dont call")
        self.same("the check-up", "the checkup")
        self.same("Okay, thank you.", "OK thank you")
        self.assertEqual(ec.normalise("nine three zero seven"), ["9", "3", "0", "7"])
        self.assertEqual(ec.normalise("twenty one"), ["2", "1"])
        self.assertEqual(ec.normalise("I am at home"), ["i", "am", "at", "home"])

    def test_different_words_stay_different(self):
        self.assertNotEqual(ec.normalise("call me at four"), ec.normalise("call me at five"))
        self.assertNotEqual(ec.normalise("Ramesh"), ec.normalise("Rajesh"))
        self.assertNotEqual(ec.normalise("four five"), ec.normalise("four nine"))

    def test_edits_count_substitutions_misses_and_additions(self):
        self.assertEqual(ec.edits("a b c d".split(), "a x c".split()), 2)
        self.assertEqual(ec.edits([], ["a"]), 1)
        self.assertEqual(ec.edits(["a", "b"], []), 2)
        self.assertEqual(ec.edits("a b c".split(), "a b c".split()), 0)
        self.assertEqual(ec.edits("a b c".split(), "b c a".split()), 2)

    def test_key_words_must_be_heard_together(self):
        hyp = ec.normalise("yes this is Ramesh Kumar, my id is 4512")
        self.assertTrue(ec.heard_key("ramesh kumar", hyp))
        self.assertTrue(ec.heard_key("4512", hyp))
        self.assertFalse(ec.heard_key("4513", hyp))
        self.assertFalse(ec.heard_key("kumar ramesh", hyp))

    def test_the_sentence_file(self):
        sentences = ec.load_sentences(ec.HERE / "ear_sentences.txt")
        self.assertEqual(len(sentences), 24)
        self.assertEqual(sentences[0].keys, ["ramesh kumar"])
        for s in sentences:                       # every key word is in its own sentence, as the scorer reads it
            self.assertTrue(s.keys, s.text)
            for k in s.keys:
                self.assertTrue(ec.heard_key(k, ec.normalise(s.text)), (s.text, k))


class LineTest(unittest.TestCase):
    def test_line_is_8khz_and_keeps_the_voice_band(self):
        x = burst(1.0, hz=1000)
        y = ec.line_audio(x)
        self.assertEqual(y.dtype, np.int16)
        self.assertEqual(len(y), 8000)
        level = np.sqrt(np.mean(y[1000:7000].astype(float) ** 2)) / 32768
        self.assertAlmostEqual(level, 0.25 / np.sqrt(2), delta=0.02)

    def test_line_removes_what_a_phone_cannot_carry(self):
        y = ec.line_audio(burst(1.0, hz=6000))                      # above the 4 kHz phone band
        self.assertLess(np.sqrt(np.mean(y[1000:7000].astype(float) ** 2)) / 32768, 0.02)

    def test_mobile_codec_passes_speech_band_audio(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore")
            y = ec.line_audio(burst(1.0, hz=500), mobile=True)
        self.assertEqual(y.dtype, np.int16)
        self.assertAlmostEqual(len(y), 8000, delta=400)
        self.assertGreater(np.sqrt(np.mean(y[2000:7000].astype(float) ** 2)) / 32768, 0.05)

    def test_a_long_pause_makes_two_turns_like_a_real_call(self):
        one = ec.utterances(ec.line_audio(np.concatenate([burst(0.8), hush(0.3), burst(0.8)])))
        two = ec.utterances(ec.line_audio(np.concatenate([burst(0.8), hush(1.2), burst(0.8)])))
        self.assertEqual(len(one), 1)
        self.assertEqual(len(two), 2)
        self.assertEqual(ec.utterances(ec.line_audio(hush(2.0))), [])

    def test_hear_gives_each_turn_to_the_ear(self):
        seen = []

        def fake(audio):
            seen.append(len(audio))
            return f"part{len(seen)}"

        audio = np.concatenate([burst(0.8), hush(1.2), burst(0.8)])
        text, pieces, ms = ec.hear(fake, audio, "line")
        self.assertEqual((text, pieces), ("part1 part2", 2))
        self.assertTrue(all(n > 0.8 * SR for n in seen))            # back at 16 kHz for Whisper
        seen.clear()
        self.assertEqual(ec.hear(fake, audio, "plain")[:2], ("part1", 1))
        self.assertEqual(seen, [len(audio)])
        self.assertEqual(ec.hear(fake, hush(1.0), "line")[:2], ("", 0))


class RecordingsTest(unittest.TestCase):
    def test_one_long_reading_is_cut_at_the_pauses(self):
        reading = np.concatenate([hush(0.5), burst(1.0), hush(0.4), burst(0.6), hush(2.0), burst(1.5), hush(2.0),
                                  burst(0.7), hush(0.5)])
        pieces = ec.split_reading(reading)
        self.assertEqual(len(pieces), 3)
        self.assertAlmostEqual(len(pieces[0]) / SR, 2.0 + 0.4, delta=0.15)  # both parts of sentence 1, with margins
        self.assertEqual(ec.split_reading(np.zeros(10, dtype=np.float32)), [])

    def test_extra_pieces_are_matched_to_the_sentences(self):
        sentences = [ec.Sentence("Call me later."), ec.Sentence("My date of birth is fifteenth August nineteen ninety"
                                                                  " two."), ec.Sentence("Okay, thank you, bye.")]
        # A pause inside sentence 2, and the pause between 1 and 2 is the shorter one: matching, not pause length.
        self.assertEqual(ec.group_pieces(["Call me later.", "My birth update this", "15th August 1992",
                                          "Okay, thank you. Bye"], sentences), [[0], [1, 2], [3]])
        # A cough heard as nothing joins a neighbour; too few pieces, or far too many, cannot be matched.
        self.assertEqual(ec.group_pieces(["", "Call me later", "my date of birth is 15 August 1992", "OK thank you bye"],
                                         sentences), [[0, 1], [2], [3]])
        self.assertIsNone(ec.group_pieces(["Call me later", "OK thank you bye"], sentences))
        self.assertIsNone(ec.group_pieces(["x"] * 10, sentences))

    def test_find_recordings(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp)
            (root / "asha").mkdir()
            write_wav(root / "asha" / "01.wav", burst(0.5))
            write_wav(root / "asha" / "03.wav", burst(0.5))
            (root / "asha" / "notes.txt").write_text("not audio")
            (root / "ravi").mkdir()
            write_wav(root / "ravi" / "all.wav", np.concatenate([burst(0.8), hush(2.0), burst(0.8), hush(2.0),
                                                                 burst(0.8)]))
            (root / "short").mkdir()
            write_wav(root / "short" / "all.wav", np.concatenate([burst(0.8), hush(2.0), burst(0.8)]))
            (root / "empty").mkdir()
            (root / "uma").mkdir()
            write_wav(root / "uma" / "all.wav", np.concatenate([burst(0.8), hush(1.1), burst(0.5), hush(1.1), burst(0.5),
                                                                hush(2.0), burst(0.8)]))
            heard = iter(["one two", "my date of birth is", "fifteenth August", "four"])
            sentences = [ec.Sentence("one two"), ec.Sentence("my date of birth is fifteenth August"),
                         ec.Sentence("four")]
            speakers, problems = ec.find_recordings(root, sentences, align=lambda audio: next(heard))
            without, _ = ec.find_recordings(root, sentences)
        self.assertEqual(sorted(speakers), ["asha", "ravi", "uma"])
        self.assertEqual(sorted(speakers["asha"]), [1, 3])
        self.assertEqual(sorted(speakers["ravi"]), [1, 2, 3])
        self.assertEqual(sorted(speakers["uma"]), [1, 2, 3])
        self.assertAlmostEqual(len(speakers["uma"][2]) / SR, 0.5 + 1.1 + 0.5 + 0.4, delta=0.15)  # both pieces, one sound
        self.assertEqual(len(problems), 4)
        self.assertTrue(any("uma" in p and "sentence 2 = pieces 2+3" in p for p in problems))
        self.assertNotIn("uma", without)                                     # no ear to match with: refused
        self.assertTrue(any("asha" in p and "2" in p for p in problems))
        self.assertTrue(any("short" in p and "2 sentences" in p for p in problems))
        self.assertTrue(any("empty" in p for p in problems))


class CheckTest(unittest.TestCase):
    def test_check_and_report(self):
        sentences = [ec.Sentence("Call me back at four.", ["4"]), ec.Sentence("This is Ramesh Kumar.", ["ramesh kumar"])]
        speakers = {"tone": {1: burst(0.8), 2: burst(0.8, hz=400)}}
        answers = iter(["Call me back at 4.", "This is Rajesh Kumar."] * 2)
        results = ec.check(speakers, sentences, {"fake": lambda audio: next(answers)}, ["plain", "line"])
        self.assertEqual(len(results), 2)
        r = results[0]
        self.assertEqual((r["words"], r["errors"], r["keys"], r["keys_heard"]), (9, 1, 2, 1))
        self.assertEqual(r["items"][1]["keys_missed"], ["ramesh kumar"])
        text = ec.report(results, ["x: no recording for sentence(s) 3 (left out)"], {"tone": -12.0})
        self.assertIn("| tone | fake | plain | 11.1% (1/9) | 1/2 |", text)
        self.assertIn('heard "This is Rajesh Kumar." (missed: ramesh kumar)', text)
        self.assertIn("no recording for sentence(s) 3", text)
        json.dumps(results)                        # the JSON report can be written


if __name__ == "__main__":
    unittest.main()
