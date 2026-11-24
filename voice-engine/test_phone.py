"""Tests for a real phone call on our speech engine, with no phone line, model or CRM: the audio maths against
Python's own G.711 code, end-of-speech detection on made-up audio, and the call loop with a fake Plivo, a fake
CRM and fake speech models.

    .venv\\Scripts\\python -m unittest test_phone
"""

import asyncio
import base64
import io
import json
import unittest
import warnings
import wave

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
    def __init__(self, replies, record=False):
        self.replies, self.heard, self.ended, self.troubles, self.last = list(replies), [], 0, [], []
        self.record, self.recordings = record, []

    def open(self, call_id):
        return phone.Reply("Hello, this is Asha. This call may be recorded.", False, "", self.record)

    def recording(self, call_id, ogg):
        self.recordings.append((call_id, ogg))
        return True

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
            recording = call.send_recording()
            if recording is not None:
                await recording
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

    def test_nobody_the_team_cannot_call_back_is_promised_a_call_back(self):
        last = lambda sent: {"event": "playedStream", "name": [m for m in sent if m["event"] == "checkpoint"][-1]["name"]}
        speech = media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)]))

        def run(crm, script, ear):
            said = []

            def voice(text):
                said.append(text)
                return tone(0.5, 440, 4000, rate=22050), 22050
            self.run_call(crm, script, stt=ear, tts=voice)
            return said

        def broken_after(n):
            heard = []

            def ear(audio):
                heard.append(1)
                if len(heard) > n:
                    raise RuntimeError("the model crashed")
                return "Fine."
            return ear

        class Withheld(FakeCrm):         # the CRM says at the opening: number withheld
            def open(self, call_id):
                return phone.Reply("Hello.", False, call_back=False)
        crm = Withheld([])
        said = run(crm, [self.START, "wait", last, speech, "wait", last, "wait"], broken_after(0))
        self.assertEqual(said, ["Hello.", phone.TROUBLE_NO_CALL_BACK])
        self.assertEqual(crm.troubles, ["turn failed: RuntimeError"])     # still reported, for the transcript

        # Opted out during the call: the turn's reply says so, and the next fault promises nothing.
        crm = FakeCrm([phone.Reply("You will not be called again.", False, call_back=False)])
        said = run(crm, [self.START, "wait", last, speech, "wait", last, speech, "wait", last, "wait"], broken_after(1))
        self.assertEqual(said[-1], phone.TROUBLE_NO_CALL_BACK)

        # The other way round: a CRM that says they can be called back again (a reply without it means yes).
        crm = Withheld([phone.Reply("Noted.", False)])
        said = run(crm, [self.START, "wait", last, speech, "wait", last, speech, "wait", last, "wait"], broken_after(1))
        self.assertEqual(said[-1], phone.TROUBLE)

        # The CRM is down as the call opens, so it never said: the call-back is promised (the outbox delivers it).
        class Down(FakeCrm):
            def open(self, call_id):
                raise ConnectionError("CRM down")
        self.assertEqual(run(Down([]), [self.START, "wait", last, "wait"], broken_after(0)), [phone.TROUBLE])

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


class Refusal(Exception):
    """What requests raises for an HTTP error: it carries the response."""

    def __init__(self, status):
        super().__init__(f"{status} error")
        self.response = type("R", (), {"status_code": status})()


class FakeLine:
    """A CRM behind ``post`` that is down (raises), answers an HTTP error, or takes the request."""

    def __init__(self, answer=None):
        self.state, self.got, self.answer = "down", [], answer or {}
        self.refuse = {}            # call id -> the HTTP error the CRM answers for that call

    def post(self, url, data, timeout, headers):
        if self.state == "down":
            raise ConnectionError("CRM not reachable")
        if isinstance(self.state, int):
            raise Refusal(self.state)
        body = data.decode()
        if json.loads(body)["call_id"] in self.refuse:
            raise Refusal(self.refuse[json.loads(body)["call_id"]])
        self.got.append((url.rsplit("/", 1)[1], json.loads(body), headers["X-Voice-Timestamp"],
                         headers["X-Voice-Signature"], body))
        answer = self.answer

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return answer
        return Res()


class OutboxTest(unittest.TestCase):
    SECRET = "s" * 40

    def setUp(self):
        import tempfile
        self.dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.dir.cleanup)
        self.now = [1_000_000.0]
        self.box = phone.Outbox(self.dir.name + "/outbox", clock=lambda: self.now[0])

    def crm(self, line, outbox=True):
        return phone.Crm("http://crm.local:8082", self.SECRET, post=line.post, outbox=self.box if outbox else None)

    def test_a_stop_and_a_promised_call_back_wait_until_the_crm_is_back(self):
        line = FakeLine({"opted_out": True, "callback_booked": True})
        crm = self.crm(line)
        self.assertIsNone(crm.trouble("c1", "opening failed: ConnectionError"))
        self.assertIsNone(crm.last_words("c1", "Stop calling me"))
        self.assertEqual(self.box.waiting(), 2)
        self.assertEqual((self.box.deliver_all(crm), self.box.waiting()), (0, 2))     # still down: kept
        # The engine restarts meanwhile: a new outbox on the same folder finds them.
        self.box = phone.Outbox(self.box.folder, clock=lambda: self.now[0])
        line.state = "up"
        self.assertEqual((self.box.deliver_all(self.crm(line)), self.box.waiting()), (2, 0))
        # In the order they were made, each freshly signed (the CRM refuses signatures older than 5 minutes).
        self.assertEqual([(step, body) for step, body, *_ in line.got],
                         [("trouble", {"call_id": "c1", "what": "opening failed: ConnectionError"}),
                          ("last", {"call_id": "c1", "said": "Stop calling me"})])
        for _, _, ts, signature, raw in line.got:
            self.assertEqual(signature, phone.sign(self.SECRET, ts, raw))
        self.assertEqual(list(self.box.folder.iterdir()), [])                         # nothing of the caller's left

    def test_when_the_crm_takes_it_at_once_nothing_is_kept(self):
        line = FakeLine({"opted_out": True})
        line.state = "up"
        self.assertTrue(self.crm(line).last_words("c1", "Stop calling me"))
        self.assertEqual(self.box.waiting(), 0)

    def test_a_request_the_crm_refuses_for_good_is_not_kept(self):
        line = FakeLine()
        for status in (400, 404):
            line.state = status
            with self.assertRaises(Refusal):
                self.crm(line).last_words("c1", "Stop calling me")
        self.assertEqual(self.box.waiting(), 0)

    def test_a_crm_with_another_secret_or_failing_keeps_it(self):
        line = FakeLine()
        for status in (401, 500, 503):
            line.state = status
            self.assertIsNone(self.crm(line).trouble("c1", "turn failed"))
        self.assertEqual(self.box.waiting(), 3)

    def test_one_the_crm_refuses_later_is_dropped_and_the_rest_still_go(self):
        line = FakeLine()
        crm = self.crm(line)
        crm.last_words("gone", "Stop calling me")
        crm.last_words("c2", "Do not call again")
        line.state, line.refuse["gone"] = "up", 404     # the first call no longer exists (for example, erased)
        with self.assertLogs("phone", "WARNING"):
            self.assertEqual((self.box.deliver_all(crm), self.box.waiting()), (1, 0))
        self.assertEqual([body["call_id"] for _, body, *_ in line.got], ["c2"])

    def test_nothing_is_kept_longer_than_a_week(self):
        line = FakeLine()
        crm = self.crm(line)
        crm.last_words("c1", "Stop calling me")
        self.now[0] += 7 * 24 * 3600 - 60
        self.assertEqual(self.box.waiting(), 1)
        self.box.deliver_all(crm)
        self.assertEqual(self.box.waiting(), 1)
        self.now[0] += 120
        line.state = "up"
        with self.assertLogs("phone", "ERROR") as logs:
            self.assertEqual(self.box.deliver_all(crm), 0)
        self.assertIn("NEVER REACHED THE CRM", logs.output[0])
        self.assertEqual((self.box.waiting(), line.got), (0, []))

    def test_a_damaged_or_foreign_file_is_deleted_not_sent(self):
        self.box.folder.mkdir(parents=True)
        (self.box.folder / "00000000000000000001-aa-last.json").write_text("{not json", encoding="utf-8")
        (self.box.folder / "00000000000000000002-bb-end.json").write_text(
            json.dumps({"step": "end", "payload": {"call_id": "c1"}, "since": self.now[0]}), encoding="utf-8")
        line = FakeLine()
        line.state = "up"
        with self.assertLogs("phone", "ERROR"):
            self.assertEqual(self.box.deliver_all(self.crm(line)), 0)
        self.assertEqual((self.box.waiting(), line.got), (0, []))

    def test_without_an_outbox_a_failure_is_raised_as_before(self):
        with self.assertRaises(ConnectionError):
            self.crm(FakeLine(), outbox=False).trouble("c1", "turn failed")

    def test_a_whole_call_while_the_crm_is_down(self):
        # The CRM is down when the call opens: the caller hears the trouble line, and the promised call-back waits in
        # the outbox; so do the words they said as they hung up. Both reach the CRM once it is back.
        line = FakeLine({"callback_booked": True, "opted_out": True})
        crm = self.crm(line)
        CallTest.run_call(self, crm, [CallTest.START, "wait", media(np.concatenate([quiet(0.3), tone(1.0)]))],
                          stt=lambda audio: "Stop calling me")
        self.assertEqual(self.box.waiting(), 2)
        line.state = "up"
        self.assertEqual(self.box.deliver_all(crm), 2)
        self.assertEqual([(step, body) for step, body, *_ in line.got],
                         [("trouble", {"call_id": "c1", "what": "opening failed: ConnectionError"}),
                          ("last", {"call_id": "c1", "said": "Stop calling me"})])


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

    def test_the_crm_says_whether_the_person_can_be_called_back(self):
        replies = []

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return replies.pop(0)

        crm = phone.Crm("http://crm.local:8082", "s" * 40, post=lambda url, data, timeout, headers: Res())
        replies[:] = [{"say": "Hello", "over": False, "call_back": False}, {"say": "Hello", "over": False},
                      {"say": "Noted.", "over": False, "call_back": False}, {"say": "Noted.", "over": False,
                                                                            "call_back": True}]
        self.assertFalse(crm.open("c1").call_back)
        self.assertTrue(crm.open("c1").call_back)       # a CRM that does not say: as before, a call-back is promised
        self.assertFalse(crm.turn("c1", "hi").call_back)
        self.assertTrue(crm.turn("c1", "hi").call_back)

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


def decoded_seconds(ogg):
    """How long an Ogg Opus file plays, decoded with PyAV."""
    import io

    import av
    with av.open(io.BytesIO(ogg)) as box:
        return sum(f.samples for f in box.decode(audio=0)) / box.streams.audio[0].rate


class RecorderTest(unittest.TestCase):
    """Both voices on one track, where the caller heard them."""

    def test_the_agent_is_placed_when_it_starts_playing(self):
        r = phone.Recorder()
        r.hear(quiet(1.0))
        r.say(tone(0.5))                    # sent after 1 s of the call: plays from 1 s
        r.say(tone(0.25, 500))              # sent at once: plays after the first piece
        mix = r.mix()
        self.assertEqual(len(mix), int(1.75 * RATE))
        self.assertLess(np.abs(mix[:RATE - 1]).max(), 200)                     # only the caller's quiet line
        self.assertGreater(np.abs(mix[RATE:int(1.5 * RATE)]).max(), 7000)      # the first piece
        self.assertGreater(np.abs(mix[int(1.5 * RATE):]).max(), 7000)          # then the second

    def test_both_voices_are_heard_when_they_overlap(self):
        r = phone.Recorder()
        r.say(tone(1.0, 300, 4000))
        r.hear(tone(1.0, 700, 4000))
        mix = r.mix().astype(np.float64)
        spectrum = np.abs(np.fft.rfft(mix))
        freqs = np.fft.rfftfreq(len(mix), 1 / RATE)
        self.assertGreater(spectrum[np.argmin(np.abs(freqs - 300))], spectrum.mean() * 50)
        self.assertGreater(spectrum[np.argmin(np.abs(freqs - 700))], spectrum.mean() * 50)

    def test_what_the_caller_talked_over_is_left_out(self):
        r = phone.Recorder()
        r.hear(quiet(1.0))
        r.say(tone(2.0))                    # queued to play from 1 s to 3 s
        r.hear(tone(0.5, 700))              # the caller talks over it at 1.5 s
        r.cut()                             # the queued audio is cleared: the rest was never played
        self.assertEqual(r.agent_end, int(1.5 * RATE))
        self.assertEqual(len(r.mix()), int(1.5 * RATE))
        r.say(tone(0.5))                    # the next reply plays from now
        self.assertEqual(r.agent[-1][0], int(1.5 * RATE))

    def test_it_is_kept_as_ogg_opus_of_the_same_length_and_small(self):
        r = phone.Recorder()
        r.say(tone(2.0, 500, 3000))         # the opening, from the start
        r.hear(tone(6.0, 300, 3000))        # the caller, for 6 s
        ogg = r.ogg()
        self.assertEqual(ogg[:4], b"OggS")
        self.assertAlmostEqual(decoded_seconds(ogg), 6.0, delta=0.1)
        self.assertLess(len(ogg), 6 * 16000 / 8 * 1.5)      # about 16 kbit/s
        self.assertEqual(phone.Recorder().ogg(), b"")

    def test_at_most_three_hours_are_kept(self):
        r = phone.Recorder()
        r.MAX_S = 2
        r.hear(quiet(1.5))
        r.hear(quiet(1.0))                  # would pass 2 s: not kept
        r.say(tone(0.5))
        r.say(tone(0.5))                    # would end at 2.5 s: not kept
        self.assertEqual((r.heard, r.agent_end), (int(1.5 * RATE), 2 * RATE))


class RecordingCallTest(unittest.TestCase):
    """A call is recorded only when the CRM's opening reply says so, and sent to it once the call is over."""

    run_call = CallTest.run_call

    SCRIPT = [{"event": "start", "start": {"streamId": "s1", "callId": "plivo-1"}}, "wait",
              media(np.concatenate([quiet(0.3), tone(1.0), quiet(0.9)])), "wait"]

    def test_a_call_to_record_is_sent_to_the_crm_with_both_voices(self):
        crm = FakeCrm([phone.Reply("Noted.", False)], record=True)
        sent, closed, call = self.run_call(crm, self.SCRIPT)
        self.assertEqual(len(crm.recordings), 1)
        call_id, ogg = crm.recordings[0]
        self.assertEqual((call_id, ogg[:4]), ("c1", b"OggS"))
        # The caller's 2.2 s, and the two replies (0.5 s each) after it: the call lasted at least as long as both.
        self.assertGreaterEqual(decoded_seconds(ogg), 2.2 - 0.05)
        self.assertIsNone(call.recorder)    # sent once, then let go
        self.assertIsNone(call.send_recording())

    def test_a_call_not_to_record_keeps_and_sends_nothing(self):
        crm = FakeCrm([phone.Reply("Noted.", False)])
        sent, closed, call = self.run_call(crm, self.SCRIPT)
        self.assertEqual(crm.recordings, [])
        self.assertIsNone(call.recorder)    # dropped as soon as the CRM's opening reply said so

    def test_a_call_whose_opening_failed_is_not_recorded(self):
        crm = FakeCrm([phone.Reply("Noted.", False)], record=True)

        def broken(call_id):
            raise RuntimeError("CRM down")
        crm.open = broken
        sent, closed, call = self.run_call(crm, self.SCRIPT)
        self.assertEqual(crm.recordings, [])
        self.assertEqual(crm.troubles, ["opening failed: RuntimeError"])

    def test_the_crm_gets_the_recording_signed_as_base64(self):
        seen = []

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return {"kept": True}

        def post(url, data, timeout, headers):
            seen.append((url, json.loads(data), timeout, headers))
            return Res()
        crm = phone.Crm("http://crm.local:8082", "s" * 40, post=post)
        self.assertTrue(crm.recording("c1", b"OggS-audio"))
        url, body, timeout, headers = seen[0]
        self.assertEqual(url, "http://crm.local:8082/api/voice/hooks/live/recording")
        self.assertEqual(body, {"call_id": "c1", "audio": base64.b64encode(b"OggS-audio").decode()})
        self.assertEqual(timeout, 120)
        self.assertEqual(headers["X-Voice-Signature"],
                         phone.sign("s" * 40, headers["X-Voice-Timestamp"], json.dumps(body, separators=(",", ":"))))

    def test_the_opening_waits_8_s_for_the_crm_and_a_turn_20(self):
        seen = []

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return {"say": "Hi", "over": False}

        def post(url, data, timeout, headers):
            seen.append((url.rsplit("/", 1)[1], timeout))
            return Res()
        crm = phone.Crm("http://crm.local:8082", "s" * 40, post=post)
        crm.open("c1")
        crm.turn("c1", "Hello")
        # The opening only reads the CRM's database; a turn waits on the brain.
        self.assertEqual(seen, [("open", 8), ("turn", 20)])

    def test_only_an_explicit_yes_from_the_crm_records(self):
        answers = [{"say": "Hi", "over": False, "record": True}, {"say": "Hi", "over": False},
                   {"say": "Hi", "over": False, "record": "yes"}]

        class Res:
            def raise_for_status(self):
                pass

            def json(self):
                return answers.pop(0)
        crm = phone.Crm("http://crm.local:8082", "s" * 40, post=lambda url, data, timeout, headers: Res())
        self.assertEqual([crm.open("c").record for _ in range(3)], [True, False, False])


class EndpointTest(unittest.TestCase):
    """engine.py's /call WebSocket, with fake speech models and a fake CRM."""

    SECRET = "s" * 40

    def setUp(self):
        import tempfile
        import engine
        self.engine = engine
        self.crm = FakeCrm([phone.Reply("Thank you. Goodbye.", True)])
        saved = (engine.VOICE_HMAC_SECRET, engine.stt_pcm, engine.tts_pcm, phone.Crm, engine.STT_MODEL, engine.NAME_HINTS,
                 engine.WARM_UP, engine.OUTBOX)
        self.real_crm = phone.Crm
        engine.WARM_UP = "off"      # no real models in these tests (WarmUpTest covers the warm-up)
        engine.VOICE_HMAC_SECRET = self.SECRET
        engine.STT_MODEL, engine.NAME_HINTS = "small.en", "auto"
        engine.stt_pcm = lambda audio: "I will pay on Friday"
        engine.tts_pcm = lambda text: (tone(0.5, 440, 4000, rate=22050), 22050)
        folder = tempfile.TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        engine.OUTBOX = phone.Outbox(folder.name)     # never the real outbox folder
        self.outboxes = []                            # the outbox each call's CRM client was given
        phone.Crm = lambda url, secret, outbox=None: self.outboxes.append(outbox) or self.crm

        def restore():
            (engine.VOICE_HMAC_SECRET, engine.stt_pcm, engine.tts_pcm, phone.Crm, engine.STT_MODEL,
             engine.NAME_HINTS, engine.WARM_UP, engine.OUTBOX) = saved
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
        self.assertIs(self.outboxes[-1], self.engine.OUTBOX)    # what the CRM cannot take now is kept

    def test_the_stream_url_says_when_nobody_can_call_them_back(self):
        from starlette.testclient import TestClient
        said = []
        self.engine.tts_pcm = lambda text: said.append(text) or (tone(0.5, 440, 4000, rate=22050), 22050)

        def down(call_id):
            raise ConnectionError("CRM down")
        self.crm.open = down        # the CRM went down after it answered: only the URL has said
        token = phone.stream_token(self.SECRET, "c1")
        for query, line in ((f"call_id=c1&token={token}&call_back=no", phone.TROUBLE_NO_CALL_BACK),
                            (f"call_id=c1&call_back=no&token={token}", phone.TROUBLE_NO_CALL_BACK),
                            (f"call_id=c1&token={token}", phone.TROUBLE),
                            (f"call_id=c1&token={token}&call_back=yes", phone.TROUBLE)):
            said.clear()
            with TestClient(self.engine.app).websocket_connect(f"/call?{query}") as ws:
                ws.send_text(json.dumps({"event": "start", "start": {"streamId": "s1", "callId": "p1"}}))
                while (m := json.loads(ws.receive_text()))["event"] != "checkpoint":
                    pass
                self.assertTrue(m["name"].startswith("end-"), query)
                ws.send_text(json.dumps({"event": "playedStream", "name": m["name"]}))
                self.assertEqual(ws.receive()["type"], "websocket.close")
            self.assertEqual(said, [line], query)
        self.assertEqual(self.crm.troubles, ["opening failed: ConnectionError"] * 4)   # each still reported

    def test_the_outbox_is_sent_again_and_shown_on_health(self):
        from starlette.testclient import TestClient
        line = FakeLine({"opted_out": True})
        engine = self.engine
        engine.CRM_URL = "http://crm.local:8082"
        self.addCleanup(setattr, engine, "CRM_URL", engine.CRM_URL)
        phone.Crm = self.real_crm
        self.real_crm("http://crm.local:8082", self.SECRET, post=line.post, outbox=engine.OUTBOX).last_words(
            "c1", "Stop calling me")                  # the CRM was down: kept
        self.assertEqual(TestClient(engine.app).get("/health").json()["undelivered"], 1)
        # The retry loop sends it through the engine's own CRM client once the CRM is back.
        engine.phone.Crm = lambda url, secret, outbox=None: self.real_crm(url, secret, post=line.post, outbox=outbox)
        line.state = "up"

        async def one_round():
            try:
                await asyncio.wait_for(engine.resend_outbox(every=60), 1.0)
            except TimeoutError:
                pass
        asyncio.run(one_round())
        self.assertEqual([(step, body) for step, body, *_ in line.got],
                         [("last", {"call_id": "c1", "said": "Stop calling me"})])
        self.assertEqual(TestClient(engine.app).get("/health").json()["undelivered"], 0)

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


class WarmUpTest(unittest.TestCase):
    """engine.py loads and runs both speech models as it starts, so the first caller does not wait in silence."""

    def setUp(self):
        import engine
        self.engine = engine
        saved = (engine.stt_pcm, engine.speak_wav, engine.warm_up, engine.WARM_UP, dict(engine._warm), engine._whisper,
                 dict(engine._said_ahead))

        def restore():
            (engine.stt_pcm, engine.speak_wav, engine.warm_up, engine.WARM_UP, warm, engine._whisper, ahead) = saved
            engine._warm.clear()
            engine._warm.update(warm)
            engine._said_ahead.clear()
            engine._said_ahead.update(ahead)
        self.addCleanup(restore)
        engine._warm.update(warmed=False, error=None)
        engine._said_ahead.clear()

    def health(self):
        from starlette.testclient import TestClient
        return TestClient(self.engine.app).get("/health").json()

    def test_both_models_are_run_once_and_health_says_so(self):
        ran = []
        self.engine.stt_pcm = lambda audio: ran.append(("ears", audio.shape, audio.dtype.name)) or ""
        self.engine.speak_wav = lambda text: ran.append(("voice", text)) or b""
        self.assertFalse(self.health()["warmed"])
        self.engine.warm_up()
        self.assertEqual(ran, [("ears", (16000,), "float32"), ("voice", "Hello."), ("voice", phone.TROUBLE),
                               ("voice", phone.TROUBLE_NO_CALL_BACK)])
        self.assertEqual((self.health()["warmed"], self.health()["warm_up_error"]), (True, None))

    def test_the_goodbyes_made_at_the_warm_up_are_said_without_waiting_on_the_voice(self):
        def wav(seconds):
            buf = io.BytesIO()
            with wave.open(buf, "wb") as w:
                w.setnchannels(1)
                w.setsampwidth(2)
                w.setframerate(22050)
                w.writeframes(np.zeros(int(22050 * seconds), dtype=np.int16).tobytes())
            return buf.getvalue()
        made = []
        self.engine.stt_pcm = lambda audio: ""
        self.engine.speak_wav = lambda text: made.append(text) or wav(0.5 if text == "Hello." else 2.0)
        self.engine.warm_up()
        made.clear()
        for line in (phone.TROUBLE, phone.TROUBLE_NO_CALL_BACK):
            pcm, rate = self.engine.tts_pcm(line)
            self.assertEqual((len(pcm), rate), (44100, 22050))
        self.assertEqual(made, [])                          # made once, at the warm-up
        self.engine.tts_pcm("Your payment is due on Friday.")
        self.assertEqual(made, ["Your payment is due on Friday."])      # anything else is made when it is said

    def test_a_missing_voice_is_reported_and_does_not_stop_the_engine(self):
        from fastapi import HTTPException

        def voice(text):
            raise HTTPException(503, "Piper voice not found at voices/x.onnx. Run download_models.py.")
        self.engine.stt_pcm = lambda audio: ""
        self.engine.speak_wav = voice
        with self.assertLogs("uvicorn.error", "WARNING"):
            self.engine.warm_up()
        h = self.health()
        self.assertFalse(h["warmed"])
        self.assertEqual(h["warm_up_error"], "HTTPException: Piper voice not found at voices/x.onnx. Run download_models.py.")

    def test_the_warm_up_starts_with_the_engine_unless_switched_off(self):
        import threading
        from starlette.testclient import TestClient
        started = threading.Event()
        self.engine.warm_up = started.set
        self.engine.WARM_UP = "on"
        with TestClient(self.engine.app) as web:
            self.assertTrue(started.wait(5))
            self.assertEqual(web.get("/health").status_code, 200)
        started.clear()
        self.engine.WARM_UP = " OFF "
        with TestClient(self.engine.app):
            self.assertFalse(started.wait(0.5))
        self.assertTrue(all(self.engine.warm_up_on(s) for s in ("on", "", "yes", "anything")))
        self.assertFalse(any(self.engine.warm_up_on(s) for s in ("off", "0", "false", "no", " No ")))

    def test_a_call_during_the_warm_up_does_not_load_the_model_a_second_time(self):
        import sys
        import threading
        import time
        import types
        loads = []

        class WhisperModel:
            def __init__(self, *args, **kwargs):
                loads.append(args)
                time.sleep(0.3)         # loading takes a while
        fake = types.ModuleType("faster_whisper")
        fake.WhisperModel = WhisperModel
        saved = sys.modules.get("faster_whisper")
        sys.modules["faster_whisper"] = fake
        try:
            self.engine._whisper = None
            got = []
            threads = [threading.Thread(target=lambda: got.append(self.engine.whisper())) for _ in range(4)]
            for t in threads:
                t.start()
            for t in threads:
                t.join()
        finally:
            if saved is None:
                sys.modules.pop("faster_whisper", None)
            else:
                sys.modules["faster_whisper"] = saved
        self.assertEqual(len(loads), 1)
        self.assertEqual(len({id(m) for m in got}), 1)


if __name__ == "__main__":
    unittest.main()
