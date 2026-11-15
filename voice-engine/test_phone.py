"""Tests for a real phone call on our speech engine, with no phone line, model or CRM: the audio maths against
Python's own G.711 code, end-of-speech detection on made-up audio, and the call loop with a fake Plivo, a fake
CRM and fake speech models.

    .venv\\Scripts\\python -m unittest test_phone
"""

import asyncio
import base64
import json
import unittest
import warnings

import numpy as np

import phone

RATE = phone.RATE


def tone(seconds, hz=300.0, level=8000.0, rate=RATE):
    t = np.arange(int(seconds * rate)) / rate
    return (level * np.sin(2 * np.pi * hz * t)).astype(np.int16)


def quiet(seconds, level=30.0, seed=1):
    return (np.random.default_rng(seed).normal(0, level, int(seconds * RATE))).astype(np.int16)


def media(pcm):
    return {"event": "media", "media": {"track": "inbound", "payload": base64.b64encode(phone.ulaw_encode(pcm)).decode()}}


class AudioTest(unittest.TestCase):

    def test_mulaw_decoding_matches_pythons_g711(self):
        with warnings.catch_warnings():
            warnings.simplefilter("ignore", DeprecationWarning)
            import audioop      # in Python up to 3.12: the reference
        codes = bytes(range(256))
        ours = phone.ulaw_decode(codes)
        ref = np.frombuffer(audioop.ulaw2lin(codes, 2), dtype=np.int16)
        self.assertTrue(np.array_equal(ours, ref))

    def test_mulaw_encoding_round_trips_every_code(self):
        codes = np.arange(256, dtype=np.uint8)
        back = np.frombuffer(phone.ulaw_encode(phone.ulaw_decode(codes.tobytes())), dtype=np.uint8)
        # 0x7F is "minus zero": it decodes to 0, which encodes as 0xFF.
        self.assertTrue(np.array_equal(back[codes != 0x7F], codes[codes != 0x7F]))
        # Any sample comes back within one quantisation step (at most ~1/16 of its size, or 8 near zero).
        x = np.linspace(-32768, 32767, 5001).astype(np.int16)
        y = phone.ulaw_decode(phone.ulaw_encode(x)).astype(np.int32)
        self.assertTrue(np.all(np.abs(y - x) <= np.maximum(np.abs(x.astype(np.int32)) / 16 + 8, 8)))

    def test_resampling_keeps_the_voice_and_removes_what_would_alias(self):
        def level(x):
            return float(np.sqrt(np.mean(x[200:-200].astype(np.float64) ** 2)))
        voice = tone(1.0, 440, 8000, rate=22050).astype(np.float32)
        down = phone.resample(voice, 22050, 8000)
        self.assertEqual(len(down), 8000)
        self.assertAlmostEqual(level(down) / level(voice), 1.0, delta=0.05)
        # Same pitch: the spectrum's peak (counting zero-crossings is thrown off by samples that are exactly 0).
        seg = down[200:-200]
        n = 1 << 18
        peak = np.fft.rfftfreq(n, 1 / 8000)[np.argmax(np.abs(np.fft.rfft(seg * np.hanning(len(seg)), n)))]
        self.assertAlmostEqual(peak, 440, delta=1)
        # 6 kHz cannot exist in 8 kHz audio: it must be filtered out, not folded down to 2 kHz.
        hiss = phone.resample(tone(1.0, 6000, 8000, rate=22050).astype(np.float32), 22050, 8000)
        self.assertLess(level(hiss) / 8000 * np.sqrt(2), 0.02)
        up = phone.resample(tone(1.0, 440, 8000).astype(np.float32), 8000, 16000)
        self.assertEqual(len(up), 16000)
        self.assertAlmostEqual(level(up) / level(tone(1.0, 440, 8000)), 1.0, delta=0.05)


class UtterancesTest(unittest.TestCase):

    def test_one_sentence_between_pauses_is_one_utterance(self):
        ears = phone.Utterances()
        out = ears.feed(np.concatenate([quiet(1.0), tone(1.2), quiet(1.0)]))
        self.assertEqual(len(out), 1)
        # The utterance holds the speech, a little before it and the 700 ms of quiet that ended it.
        self.assertAlmostEqual(len(out[0]) / RATE, 1.2 + 0.7 + 0.2, delta=0.15)

    def test_a_short_pause_inside_a_sentence_does_not_cut_it(self):
        ears = phone.Utterances()
        out = ears.feed(np.concatenate([quiet(0.5), tone(0.6), quiet(0.4), tone(0.6), quiet(1.0)]))
        self.assertEqual(len(out), 1)

    def test_line_noise_and_clicks_are_not_speech(self):
        ears = phone.Utterances()
        self.assertEqual(ears.feed(quiet(3.0, level=60)), [])
        self.assertEqual(ears.feed(np.concatenate([tone(0.1), quiet(1.5)])), [])

    def test_talking_over_the_agent_needs_300_ms(self):
        ears = phone.Utterances()
        ears.feed(np.concatenate([quiet(0.5), tone(0.2)]))
        self.assertFalse(ears.talking())
        ears.feed(tone(0.2))
        self.assertTrue(ears.talking())


class FakeCrm:
    def __init__(self, replies):
        self.replies, self.heard, self.ended, self.troubles, self.last = list(replies), [], 0, [], []

    def open(self, call_id):
        return phone.Reply("Hello, this is Asha.", False)

    def turn(self, call_id, said):
        self.heard.append(said)
        return self.replies.pop(0)

    def end(self, call_id):
        self.ended += 1

    def trouble(self, call_id, what):
        self.troubles.append(what)
        return True

    def last_words(self, call_id, said):
        self.last.append(said)
        return "stop" in said.lower()


class CallTest(unittest.TestCase):

    def run_call(self, crm, script, stt=None, tts=None):
        """Plays ``script`` (Plivo messages, or "wait" for a pause) into a call; returns what was sent to Plivo."""
        sent, closed = [], []

        async def send(m):
            sent.append(m)

        async def close():
            closed.append(True)

        async def go():
            call = phone.Call("c1", send, crm, stt or (lambda audio: "I want to pay on Friday"),
                              tts or (lambda text: (tone(0.5, 440, 4000, rate=22050), 22050)), close)
            for step in script:
                if step == "wait":
                    await asyncio.sleep(0.3)
                else:
                    await call.handle(step(sent) if callable(step) else step)
            await asyncio.sleep(0.3)
            last = call.stop()
            if last is not None:
                await last
            return call
        call = asyncio.run(go())
        return sent, closed, call

    def test_the_agent_opens_listens_answers_and_hangs_up_after_its_goodbye(self):
        crm = FakeCrm([phone.Reply("Thank you, noted. Goodbye.", True)])
        played = lambda name: (lambda sent: {"event": "playedStream", "streamId": "s1", "name": name})
        sent, closed, call = self.run_call(crm, [
            {"event": "start", "start": {"streamId": "s1", "callId": "plivo-1"}}, "wait",
            played("say-1"),
            media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)])), "wait",
            played("end-2"), "wait"])
        plays = [m for m in sent if m["event"] == "playAudio"]
        marks = [m["name"] for m in sent if m["event"] == "checkpoint"]
        self.assertEqual(marks, ["say-1", "end-2"])
        self.assertEqual(crm.heard, ["I want to pay on Friday"])
        # Piper's 0.5 s at 22 kHz becomes 0.5 s of 8 kHz mu-law (4,000 bytes) per reply.
        first = base64.b64decode(plays[0]["media"]["payload"])
        self.assertEqual((len(first), plays[0]["media"]["contentType"], plays[0]["media"]["sampleRate"]),
                         (4000, "audio/x-mulaw", 8000))
        self.assertEqual(crm.ended, 1)
        self.assertEqual(closed, [True])
        self.assertTrue(call.done)
        self.assertEqual(crm.last, [])          # everything the caller said reached the CRM as a turn

    def test_a_call_the_crm_transferred_to_the_desk_is_left_for_plivo_to_close(self):
        crm = FakeCrm([phone.Reply("I am connecting you to a member of our team now. Please stay on the line.", True)])
        crm.end = lambda call_id: setattr(crm, "ended", crm.ended + 1) or True     # the CRM transferred the call
        played = lambda name: (lambda sent: {"event": "playedStream", "streamId": "s1", "name": name})
        sent, closed, call = self.run_call(crm, [
            {"event": "start", "start": {"streamId": "s1", "callId": "plivo-1"}}, "wait",
            played("say-1"),
            media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)])), "wait",
            played("end-2"), "wait"])
        self.assertEqual([m["name"] for m in sent if m["event"] == "checkpoint"], ["say-1", "end-2"])
        self.assertEqual((crm.ended, call.done, call.transferred), (1, True, True))
        self.assertEqual(closed, [])            # closing it could cut the caller off before Plivo moves the call

    def test_after_a_transfer_the_stream_is_read_until_plivo_closes_it(self):
        messages, closed = iter(['{"event":"media"}', '{"event":"media"}']), []

        async def receive():
            try:
                return next(messages)
            except StopIteration:
                raise ConnectionError("Plivo closed the stream") from None

        async def close():
            closed.append(True)
        with self.assertRaises(ConnectionError):
            asyncio.run(phone.await_transfer(receive, close, wait=2))
        self.assertEqual(closed, [])

    def test_after_a_transfer_the_stream_is_closed_if_plivo_never_does(self):
        closed = []

        async def receive():
            await asyncio.sleep(60)

        async def close():
            closed.append(True)
        asyncio.run(phone.await_transfer(receive, close, wait=0.2))
        self.assertEqual(closed, [True])

    def test_the_contact_name_from_the_crm_is_the_ears_hint_on_every_turn(self):
        class Named(FakeCrm):
            def open(self, call_id):
                return phone.Reply("Hello, am I speaking with Lakshmi?", False, "Lakshmi Narayanan")

        hints = []

        def ear(audio, *hint):
            hints.append(hint)
            return "This is Lakshmi Narayanan"

        speech = media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))
        crm = Named([phone.Reply("Thank you.", False), phone.Reply("Noted.", False)])
        self.run_call(crm, [{"event": "start", "start": {"streamId": "s1"}}, "wait", speech, "wait", speech, "wait"],
                      stt=ear)
        self.assertEqual(hints, [("Lakshmi Narayanan",), ("Lakshmi Narayanan",)])
        self.assertEqual(crm.heard, ["This is Lakshmi Narayanan"] * 2)

        hints.clear()                               # an unknown caller: no name, so no hint
        self.run_call(FakeCrm([phone.Reply("Noted.", False)]),
                      [{"event": "start", "start": {"streamId": "s1"}}, "wait", speech, "wait"], stt=ear)
        self.assertEqual(hints, [()])

    def hold(self, seconds):
        saved = phone.Call.HOLD_S
        phone.Call.HOLD_S = seconds
        self.addCleanup(setattr, phone.Call, "HOLD_S", saved)

    START = {"event": "start", "start": {"streamId": "s1"}}

    def test_a_pause_inside_a_sentence_still_makes_one_turn(self):
        heard_lengths = []

        def ear(audio):                     # the first part alone, or both parts heard together
            heard_lengths.append(len(audio) / phone.STT_RATE)
            return "My date of birth is..." if len(audio) < 3 * phone.STT_RATE else "My date of birth is 15th August."

        crm = FakeCrm([phone.Reply("Thank you.", False)])
        first = media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))
        rest = media(np.concatenate([tone(1.0), quiet(0.9)]))
        self.run_call(crm, [self.START, "wait", first, rest, "wait", "wait"], stt=ear)
        self.assertEqual(crm.heard, ["My date of birth is 15th August."])
        self.assertEqual(len(heard_lengths), 2)
        self.assertGreater(heard_lengths[1], heard_lengths[0] + 1.5)    # the second hearing has both parts

    def test_an_unfinished_turn_goes_on_after_the_wait_when_nothing_follows(self):
        self.hold(0.2)
        crm = FakeCrm([phone.Reply("Sorry, what is your date of birth?", False)])
        speech = media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))
        self.run_call(crm, [self.START, "wait", speech, "wait", "wait"], stt=lambda audio: "My date of birth is...")
        self.assertEqual(crm.heard, ["My date of birth is..."])

    def test_a_finished_sentence_is_not_held_back(self):
        self.hold(30)
        speech = media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))
        crm = FakeCrm([phone.Reply("Noted.", False)])
        self.run_call(crm, [self.START, "wait", speech, "wait"], stt=lambda audio: "I will pay on Friday.")
        self.assertEqual(crm.heard, ["I will pay on Friday."])
        crm = FakeCrm([phone.Reply("Noted.", False)])       # the same, unfinished: still waiting when the call ends
        self.run_call(crm, [self.START, "wait", speech, "wait"], stt=lambda audio: "I will pay on the")
        self.assertEqual(crm.heard, [])

    def test_a_turn_takes_in_at_most_two_more_parts(self):
        self.hold(0.2)
        lengths = []

        def ear(audio):
            lengths.append(round(len(audio) / phone.STT_RATE, 1))
            return "and um,"
        part = media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))
        crm = FakeCrm([phone.Reply("Go on.", False), phone.Reply("Go on.", False)])
        self.run_call(crm, [self.START, "wait", part, part, part, part, "wait", "wait", "wait"], stt=ear)
        self.assertEqual(crm.heard, ["and um,", "and um,"])        # parts 1-3 as one turn, then part 4 alone
        self.assertEqual(len(lengths), 4)
        self.assertAlmostEqual(lengths[2], 3 * lengths[0], delta=0.2)
        self.assertEqual(lengths[3], lengths[0])

    def test_the_caller_can_talk_over_the_agent(self):
        crm = FakeCrm([phone.Reply("Okay.", False)])
        sent, _, _ = self.run_call(crm, [
            {"event": "start", "start": {"streamId": "s1"}}, "wait",
            media(np.concatenate([quiet(0.3), tone(0.5)]))])
        self.assertIn({"event": "clearAudio", "streamId": "s1"}, sent)

    def test_silence_or_noise_is_never_sent_to_the_crm(self):
        crm = FakeCrm([])
        sent, _, _ = self.run_call(crm, [{"event": "start", "start": {"streamId": "s1"}}, "wait",
                                         media(quiet(3.0, level=60)), "wait"])
        self.assertEqual(crm.heard, [])
        self.assertNotIn("clearAudio", [m["event"] for m in sent])

    def test_a_crm_failure_is_said_and_the_call_ends(self):
        class Broken(FakeCrm):
            def open(self, call_id):
                raise ConnectionError("CRM down")
        crm = Broken([])
        played = lambda sent: {"event": "playedStream", "name": [m for m in sent if m["event"] == "checkpoint"][-1]["name"]}
        sent, closed, _ = self.run_call(crm, [{"event": "start", "start": {"streamId": "s1"}}, "wait", played, "wait"])
        self.assertTrue([m for m in sent if m["event"] == "playAudio"])
        self.assertEqual([m["name"] for m in sent if m["event"] == "checkpoint"], ["end-1"])
        self.assertEqual(crm.ended, 1)
        self.assertEqual(closed, [True])
        self.assertEqual(crm.troubles, ["opening failed: ConnectionError"])     # the CRM is asked to book the call-back

    def test_a_failed_turn_books_the_call_back_the_caller_is_promised(self):
        crm = FakeCrm([])

        def ear(audio):
            raise RuntimeError("the model crashed")
        last = lambda sent: {"event": "playedStream", "name": [m for m in sent if m["event"] == "checkpoint"][-1]["name"]}
        speech = media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))
        sent, closed, _ = self.run_call(crm, [self.START, "wait", last, speech, "wait", last, "wait"], stt=ear)
        self.assertEqual([m["name"] for m in sent if m["event"] == "checkpoint"], ["say-1", "end-2"])
        self.assertEqual(crm.troubles, ["turn failed: RuntimeError"])
        self.assertEqual((crm.ended, closed), (1, [True]))

    def test_when_the_voice_fails_the_fault_is_reported_and_the_call_ends_instead_of_going_silent(self):
        crm = FakeCrm([])

        def voice(text):
            raise OSError("voice model missing")
        sent, closed, call = self.run_call(crm, [self.START, "wait"], tts=voice)
        self.assertFalse([m for m in sent if m["event"] == "playAudio"])
        self.assertEqual(crm.troubles, ["speaking failed: OSError"])
        self.assertEqual((crm.ended, closed, call.done), (1, [True], True))

    def test_a_crm_that_is_down_for_the_report_too_does_not_stop_the_goodbye(self):
        class Down(FakeCrm):
            def open(self, call_id):
                raise ConnectionError("CRM down")

            def trouble(self, call_id, what):
                self.troubles.append(what)
                raise ConnectionError("CRM down")
        crm = Down([])
        last = lambda sent: {"event": "playedStream", "name": [m for m in sent if m["event"] == "checkpoint"][-1]["name"]}
        sent, closed, _ = self.run_call(crm, [self.START, "wait", last, "wait"])
        self.assertEqual([m["name"] for m in sent if m["event"] == "checkpoint"], ["end-1"])
        self.assertEqual((crm.troubles, crm.ended, closed), (["opening failed: ConnectionError"], 1, [True]))


    # ------------------------------------------------------------------ the caller's last words at the hang-up

    def test_words_cut_off_by_the_hang_up_reach_the_crm_as_last_words(self):
        # "Stop calling me" and the caller hangs up at once: the 700 ms of quiet that ends a turn never comes.
        crm = FakeCrm([])
        self.run_call(crm, [self.START, "wait", media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.2)]))],
                      stt=lambda audio: "Stop calling me")
        self.assertEqual((crm.heard, crm.last), ([], ["Stop calling me"]))

    def test_a_turn_still_being_heard_at_the_hang_up_is_sent_as_last_words(self):
        import time
        crm = FakeCrm([phone.Reply("Okay.", False)])

        def slow(audio):                    # the ear is still working when the stream closes
            time.sleep(0.6)
            return "Remove my number"
        self.run_call(crm, [self.START, "wait", media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))], stt=slow)
        self.assertEqual((crm.heard, crm.last), ([], ["Remove my number"]))

    def test_what_the_caller_says_over_the_goodbye_is_sent_as_last_words(self):
        crm = FakeCrm([phone.Reply("Thank you, noted. Goodbye.", True)])
        said = iter(["I will pay on Friday", "And do not call me again"])
        speech = media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))
        sent, _, _ = self.run_call(crm, [self.START, "wait", speech, "wait", speech, "wait"],
                                   stt=lambda audio: next(said))
        self.assertEqual([m["name"] for m in sent if m["event"] == "checkpoint"], ["say-1", "end-2"])  # no reply to it
        self.assertEqual((crm.heard, crm.last), (["I will pay on Friday"], ["And do not call me again"]))

    def test_a_click_at_the_hang_up_or_nothing_at_all_sends_nothing(self):
        for tail in (tone(0.1), quiet(0.5, level=60)):
            crm = FakeCrm([])
            heard = []
            self.run_call(crm, [self.START, "wait", media(np.concatenate([quiet(0.3), tail]))],
                          stt=lambda audio: heard.append(audio) or "Hello")
            self.assertEqual((heard, crm.last), ([], []))

    def test_a_crm_that_is_down_for_the_last_words_does_not_break_the_hang_up(self):
        class Down(FakeCrm):
            def last_words(self, call_id, said):
                self.last.append(said)
                raise ConnectionError("CRM down")
        crm = Down([])
        _, _, call = self.run_call(crm, [self.START, "wait", media(np.concatenate([quiet(0.3), tone(1.0)]))],
                                   stt=lambda audio: "Stop calling me")
        self.assertEqual((crm.last, call.done), (["Stop calling me"], True))


class UnfinishedTest(unittest.TestCase):

    def test_cut_off_sentences(self):
        for text in ("My birth of death is...", "My date of birth is", "My customer ID is, um,", "It is the",
                     "My name is Ramesh and", "Um.", "uh", "The amount is …", "I will pay on the -",
                     "My phone number", "It's for my.", "Sending it to"):
            self.assertTrue(phone.unfinished(text), text)

    def test_finished_sentences(self):
        for text in ("Yes, it is.", "My date of birth is 15th August 1992.", "I will pay on Friday.", "Yes",
                     "No, thank you.", "Who is this?", "Ramesh Kumar", "Okay.", "", "...", "It is 4 5 6 7.",
                     "That is all I wanted", "I paid it on Monday", "My phone number is 9840122356",
                     "It is 4 5 6 7"):
            self.assertFalse(phone.unfinished(text), text)


class CrmTest(unittest.TestCase):

    def test_requests_are_signed_like_the_crm_expects(self):
        seen = {}

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return {"say": "Hi", "over": False}

        def post(url, data, timeout, headers):
            seen.update(url=url, data=data, headers=headers)
            return Res()
        reply = phone.Crm("http://crm.local:8082/", "s" * 40, post=post).turn("c1", "hello")
        self.assertEqual(reply, phone.Reply("Hi", False))
        self.assertEqual(seen["url"], "http://crm.local:8082/api/voice/hooks/live/turn")
        body = seen["data"].decode()
        self.assertEqual(json.loads(body), {"call_id": "c1", "said": "hello"})
        ts = seen["headers"]["X-Voice-Timestamp"]
        self.assertEqual(seen["headers"]["X-Voice-Signature"], phone.sign("s" * 40, ts, body))

    def test_a_fault_is_reported_signed_and_says_whether_a_call_back_was_booked(self):
        seen = []

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return {"callback_booked": True}

        def post(url, data, timeout, headers):
            seen.append((url, json.loads(data), headers))
            return Res()
        crm = phone.Crm("http://crm.local:8082", "s" * 40, post=post)
        self.assertTrue(crm.trouble("c1", "turn failed: " + "x" * 300))
        url, body, headers = seen[0]
        self.assertEqual(url, "http://crm.local:8082/api/voice/hooks/live/trouble")
        self.assertEqual((body["call_id"], len(body["what"])), ("c1", 200))
        raw = json.dumps(body, separators=(",", ":"))
        self.assertEqual(headers["X-Voice-Signature"], phone.sign("s" * 40, headers["X-Voice-Timestamp"], raw))

    def test_the_last_words_are_signed_and_say_whether_the_caller_was_opted_out(self):
        seen = []

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return {"opted_out": True}

        def post(url, data, timeout, headers):
            seen.append((url, json.loads(data), headers))
            return Res()
        crm = phone.Crm("http://crm.local:8082", "s" * 40, post=post)
        self.assertTrue(crm.last_words("c1", "Stop calling me " + "x" * 600))
        url, body, headers = seen[0]
        self.assertEqual(url, "http://crm.local:8082/api/voice/hooks/live/last")
        self.assertEqual((body["call_id"], len(body["said"])), ("c1", 500))
        raw = json.dumps(body, separators=(",", ":"))
        self.assertEqual(headers["X-Voice-Signature"], phone.sign("s" * 40, headers["X-Voice-Timestamp"], raw))

    def test_the_end_reply_says_whether_the_crm_transferred_the_call(self):
        answers = [{"hung_up": False, "transferred": True}, {"hung_up": True, "transferred": False}, {"hung_up": True}]
        seen = []

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return answers.pop(0)

        def post(url, data, timeout, headers):
            seen.append((url, json.loads(data)))
            return Res()
        crm = phone.Crm("http://crm.local:8082", "s" * 40, post=post)
        self.assertEqual([crm.end("c1"), crm.end("c1"), crm.end("c1")], [True, False, False])
        self.assertEqual(seen[0], ("http://crm.local:8082/api/voice/hooks/live/end", {"call_id": "c1"}))

    def test_the_opening_reply_brings_the_contacts_name(self):
        answers = [{"say": "Hello", "over": False, "hint": "  Mohammed Irfan "}, {"say": "Hello", "over": False},
                   {"say": "Hi", "over": False, "hint": "x" * 300}]

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return answers.pop(0)

        crm = phone.Crm("http://crm.local:8082", "s" * 40, post=lambda url, data, timeout, headers: Res())
        self.assertEqual(crm.open("c1"), phone.Reply("Hello", False, "Mohammed Irfan"))
        self.assertEqual(crm.open("c2"), phone.Reply("Hello", False, ""))
        self.assertEqual(len(crm.open("c3").hint), 100)

    def test_the_stream_token_matches_the_crm(self):
        # The same value is asserted for PlivoHooksController.streamToken in PlivoSignatureTest (Java).
        self.assertEqual(phone.stream_token("test-only-voice-secret-0123456789-abcdefgh", "6f1c2b8e-0000-4000-8000-000000000001"),
                         "c4af2d96e8c7c170948d98741c6e3201f8a57ae74c2642903899926cc2e7b5df")


class EndpointTest(unittest.TestCase):
    """engine.py's /call WebSocket, with fake speech models and a fake CRM."""

    SECRET = "s" * 40

    def setUp(self):
        import engine
        self.engine = engine
        self.crm = FakeCrm([phone.Reply("Thank you. Goodbye.", True)])
        saved = (engine.VOICE_HMAC_SECRET, engine.stt_pcm, engine.tts_pcm, phone.Crm, engine.STT_MODEL, engine.NAME_HINTS)
        engine.VOICE_HMAC_SECRET = self.SECRET
        engine.STT_MODEL, engine.NAME_HINTS = "small.en", "auto"
        engine.stt_pcm = lambda audio: "I will pay on Friday"
        engine.tts_pcm = lambda text: (tone(0.5, 440, 4000, rate=22050), 22050)
        phone.Crm = lambda url, secret: self.crm

        def restore():
            (engine.VOICE_HMAC_SECRET, engine.stt_pcm, engine.tts_pcm, phone.Crm, engine.STT_MODEL,
             engine.NAME_HINTS) = saved
        self.addCleanup(restore)

    def refused(self, url):
        from starlette.testclient import TestClient
        from starlette.websockets import WebSocketDisconnect
        with self.assertRaises(WebSocketDisconnect) as e:
            with TestClient(self.engine.app).websocket_connect(url):
                pass
        self.assertEqual(e.exception.code, 1008)

    def test_a_wrong_or_missing_token_is_refused(self):
        self.refused("/call?call_id=c1&token=wrong")
        self.refused("/call?call_id=c1")
        self.refused(f"/call?token={phone.stream_token(self.SECRET, 'c1')}")
        # A token for another call does not open this one; nor does a non-ASCII token crash the check.
        self.refused(f"/call?call_id=c2&token={phone.stream_token(self.SECRET, 'c1')}")
        self.refused("/call?call_id=c1&token=%E0%AE%85")
        # No secret set: nothing gets in, not even a token made with an empty secret.
        self.engine.VOICE_HMAC_SECRET = ""
        self.refused(f"/call?call_id=c1&token={phone.stream_token('', 'c1')}")

    def test_a_whole_call_over_the_socket(self):
        from starlette.testclient import TestClient

        def until_checkpoint(ws):
            events = []
            while True:
                m = json.loads(ws.receive_text())
                events.append(m["event"])
                if m["event"] == "checkpoint":
                    return events, m["name"]

        with TestClient(self.engine.app).websocket_connect(f"/call?call_id=c1&token={phone.stream_token(self.SECRET, 'c1')}") as ws:
            ws.send_text(json.dumps({"event": "start", "start": {"streamId": "s1", "callId": "p1"}}))
            events, mark = until_checkpoint(ws)
            self.assertIn("playAudio", events)
            ws.send_text(json.dumps({"event": "playedStream", "name": mark}))
            ws.send_text(json.dumps(media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))))
            events, mark = until_checkpoint(ws)
            self.assertTrue(mark.startswith("end-"))
            ws.send_text(json.dumps({"event": "playedStream", "name": mark}))
            self.assertEqual(ws.receive()["type"], "websocket.close")
        self.assertEqual(self.crm.heard, ["I will pay on Friday"])
        self.assertEqual(self.crm.ended, 1)

    def test_the_socket_closing_mid_sentence_sends_the_last_words(self):
        import time
        from starlette.testclient import TestClient
        self.engine.stt_pcm = lambda audio: "Stop calling me"
        # The test client cancels the handler when the socket closes (uvicorn does not): the last words go on anyway.
        with TestClient(self.engine.app) as client:
            with client.websocket_connect(f"/call?call_id=c1&token={phone.stream_token(self.SECRET, 'c1')}") as ws:
                ws.send_text(json.dumps({"event": "start", "start": {"streamId": "s1", "callId": "p1"}}))
                while json.loads(ws.receive_text())["event"] != "checkpoint":
                    pass
                ws.send_text(json.dumps(media(np.concatenate([quiet(0.3), tone(1.0)]))))
            # The caller hung up (the socket closed) before 700 ms of quiet: still heard and sent.
            for _ in range(60):
                if self.crm.last:
                    break
                time.sleep(0.05)
        self.assertEqual((self.crm.heard, self.crm.last), ([], ["Stop calling me"]))

    def test_the_engine_gives_whisper_the_contacts_name(self):
        heard_with = []

        def stt(audio, hint=""):
            heard_with.append(hint)
            return "I will pay on Friday"
        self.engine.stt_pcm = stt
        self.crm.open = lambda call_id: phone.Reply("Hello, am I speaking with Irfan?", False, "Mohammed Irfan")
        self.test_a_whole_call_over_the_socket()
        self.assertEqual(heard_with, ["Mohammed Irfan"])

    def test_base_en_is_not_given_the_name_unless_asked(self):
        heard_with = []

        def stt(audio, hint=""):
            heard_with.append(hint)
            return "I will pay on Friday"
        self.engine.stt_pcm = stt
        self.crm.open = lambda call_id: phone.Reply("Hello, am I speaking with Irfan?", False, "Mohammed Irfan")
        self.engine.STT_MODEL = "base.en"
        self.test_a_whole_call_over_the_socket()
        self.engine.NAME_HINTS = "on"
        self.crm.heard.clear()
        self.crm.ended = 0
        self.crm.replies = [phone.Reply("Thank you. Goodbye.", True)]
        self.test_a_whole_call_over_the_socket()
        self.assertEqual(heard_with, ["", "Mohammed Irfan"])

    def test_which_ears_are_given_the_name(self):
        on = self.engine.hints_on
        for model in ("small.en", "medium.en", "large-v3", r"C:\models\faster-whisper-small.en", "models/small.en/"):
            self.assertTrue(on(model, "auto"), model)
            self.assertFalse(on(model, "off"), model)
        for model in ("base.en", "tiny.en", "base", r"C:\models\faster-whisper-base.en", "models/base.en/"):
            self.assertFalse(on(model, "auto"), model)
            self.assertTrue(on(model, " ON "), model)
        self.assertFalse(on("base.en", "something else"))


if __name__ == "__main__":
    unittest.main()
