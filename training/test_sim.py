"""Tests for the call simulator, with a fake model in place of Ollama, plus a check of the source files themselves.

    python -m unittest test_sim
"""

import random
import unittest
from pathlib import Path

import callkit
import sim
from callkit import all_scripts, check
from scenarios import SCENARIOS


def script(name):
    return next(s for s in all_scripts() if s.name == name)


def scenario(sid):
    return next(s for s in SCENARIOS if s.id == sid)


def tool(name, **args):
    return {"content": "", "tool_calls": [{"id": "x", "type": "function", "function": {"name": name, "arguments": args}}]}


def say(text):
    return {"content": text, "tool_calls": []}


class FakeModel:
    """Plays both sides: ``caller(msgs)`` and ``agent(msgs, reminded)`` return what the model would."""

    def __init__(self, caller, agent):
        self.caller, self.agent, self.agent_calls = caller, agent, 0

    def __call__(self, model, msgs, tools=None, **kw):
        if tools is None:
            return say(self.caller(msgs))
        self.agent_calls += 1
        return self.agent(msgs, "REMINDER (private)" in msgs[0]["content"])


class SimTest(unittest.TestCase):

    def setUp(self):
        self.real = sim.ollama_chat

    def tearDown(self):
        sim.ollama_chat = self.real

    def run_call(self, fake, script_name, scenario_id, coach=True):
        sim.ollama_chat = fake
        return sim.simulate(script(script_name), scenario(scenario_id), random.Random(1), "agent", "caller", coach=coach)

    def test_a_promise_without_its_tool_is_retried_and_only_the_fixed_turn_is_kept(self):
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("Of course, they will be in touch soon. Thank you.")
            if reminded:
                return tool("request_human_handoff", reason_code="CALLER_REQUEST", summary="Wants a person.")
            return say("I will transfer your call right away.")
        rec, why = self.run_call(FakeModel(lambda m: "Can I talk to a real person please?", agent), "Check-up recall", "ask_person")
        self.assertEqual(why, "")
        self.assertEqual(check(rec), [])
        spoken = [m["content"] for m in rec["messages"] if m["role"] == "assistant"]
        self.assertNotIn("I will transfer your call right away.", spoken)
        self.assertNotIn("REMINDER", rec["messages"][0]["content"])

    def test_a_goodbye_without_an_outcome_stops_the_call_instead_of_looping(self):
        fake = FakeModel(lambda m: "Okay, bye.", lambda msgs, reminded: say("Okay. Goodbye."))
        rec, why = self.run_call(fake, "Enquiry follow-up", "not_interested")
        self.assertEqual(why, "the agent said goodbye without recording how the call ended")
        self.assertLess(fake.agent_calls, 5)

    def test_when_testing_a_model_its_mistakes_are_not_retried(self):
        fake = FakeModel(lambda m: "Not interested.", lambda msgs, reminded: say("Okay. Goodbye."))
        rec, why = self.run_call(fake, "Enquiry follow-up", "not_interested", coach=False)
        self.assertEqual(why, "the agent said goodbye without recording how the call ended")
        self.assertEqual(fake.agent_calls, 1)

    def test_a_goodbye_after_a_booked_call_back_ends_the_call_as_the_crm_does(self):
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("Our team will call you back. Thank you, goodbye.")
            return tool("schedule_callback", preferred_time_text="this evening", reason="Busy; asked for a call later.")
        fake = FakeModel(lambda m: "I am driving, please call me this evening.", agent)
        rec, why = self.run_call(fake, "Enquiry follow-up", "busy", coach=False)
        self.assertEqual(why, "")
        self.assertEqual(rec["messages"][-1]["content"], "Our team will call you back. Thank you, goodbye.")
        # The live CRM keeps the CALLBACK_REQUESTED outcome; the model alone still forgot log_call_outcome.
        self.assertEqual(check(rec, crm_net=True), [])
        self.assertIn("ends without log_call_outcome, opt_out or request_human_handoff", check(rec))

    def test_a_goodbye_after_the_identity_lock_out_counts_as_verification_failed(self):
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                if '"attempts_left":0' in msgs[-1]["content"]:
                    return say("I am sorry, I could not verify your details. Goodbye.")
                return say("That did not match our records. Could you tell me again?")
            return tool("verify_identity", full_name="Somebody Else", answers={"customer_id": "0000"})
        fake = FakeModel(lambda m: "It is 0000.", agent)
        rec, why = self.run_call(fake, "Payment reminder", "verify_fail", coach=False)
        self.assertEqual(why, "")
        self.assertEqual(check(rec, crm_net=True), [])
        self.assertIn("expected a call to log_call_outcome", check(rec))
        self.assertEqual(callkit.settles_the_call("verify_identity", {"ok": True, "data": {"verified": False, "attempts_left": 2}}), None)
        self.assertEqual(callkit.settles_the_call("schedule_callback", {"ok": False}), None)

    def test_a_made_up_date_is_never_spoken_and_the_model_tries_again(self):
        def agent(msgs, reminded):
            if "PRIVATE NOTE" in msgs[0]["content"]:
                return tool("log_call_outcome", outcome_code="NOT_INTERESTED") if msgs[-1]["role"] != "tool" \
                    else say("No problem. Thank you, goodbye.")
            return say("Your visit is on Thursday, 20th April. Can you come?")
        fake = FakeModel(lambda m: "No, I'm not interested anymore.", agent)
        rec, why = self.run_call(fake, "Enquiry follow-up", "not_interested", coach=False)
        self.assertEqual(why, "")
        spoken = " ".join(m.get("content") or "" for m in rec["messages"] if m["role"] == "assistant")
        self.assertNotIn("Thursday", spoken)
        # "20" passes on its own: the script's style rule says "under 20 words", so it counts as heard. The weekday
        # and month are what give the invented date away.
        self.assertEqual(rec["meta"]["crm_corrections"], [["thursday", "april"]])

    def test_a_model_that_keeps_inventing_facts_is_handed_to_a_person(self):
        fake = FakeModel(lambda m: "Okay.", lambda msgs, reminded: say("You owe 5,000 rupees by Friday."))
        rec, why = self.run_call(fake, "Enquiry follow-up", "not_interested", coach=False)
        self.assertEqual(why, "the CRM handed the call to a person: the agent kept stating facts it was not given")
        self.assertEqual(len(rec["meta"]["crm_corrections"]), 2)
        self.assertNotIn("5,000", " ".join(m.get("content") or "" for m in rec["messages"]))

    def test_hidden_reasoning_is_never_spoken_but_is_counted(self):
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("<think>\nThe caller said no.\n</think>\n\nNo problem. Thank you, goodbye.")
            return tool("log_call_outcome", outcome_code="NOT_INTERESTED")
        rec, why = self.run_call(FakeModel(lambda m: "No, I'm not interested anymore.", agent), "Enquiry follow-up", "not_interested", coach=False)
        self.assertEqual(why, "")
        self.assertEqual(rec["messages"][-1]["content"], "No problem. Thank you, goodbye.")
        self.assertEqual(rec["meta"]["thinking_turns"], 1)

    def test_an_invented_hand_over_reason_is_refused(self):
        call = callkit.new_call(script("Enquiry follow-up"), random.Random(3))
        res = __import__("json").loads(call.run("request_human_handoff", {"reason_code": "DISPUTE", "summary": "x"}))
        self.assertEqual((res["ok"], res["error_code"]), (False, "INVALID_ARGUMENT"))
        self.assertIn("COMPLAINT_OR_DISPUTE", res["error_message"])
        self.assertIsNone(call.outcome)
        self.assertTrue(__import__("json").loads(call.run("request_human_handoff", {"reason_code": "CALLER_REQUEST", "summary": "x"}))["ok"])

    def test_a_caller_who_only_gives_a_stage_direction_is_asked_again(self):
        replies = iter(["*hangs up*", "No, I'm not interested anymore."])
        caller = lambda msgs: next(replies, "Okay.")

        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("No problem at all. Goodbye.")
            return tool("log_call_outcome", outcome_code="NOT_INTERESTED")
        rec, why = self.run_call(FakeModel(caller, agent), "Enquiry follow-up", "not_interested")
        self.assertEqual(why, "")
        self.assertEqual(rec["messages"][3], {"role": "user", "content": "No, I'm not interested anymore."})

    def test_an_unreadable_model_reply_fails_the_call_instead_of_stopping_the_test(self):
        import urllib.error

        def agent(msgs, reminded):
            raise urllib.error.HTTPError("http://localhost:11434", 500, "Internal Server Error", {}, None)
        rec, why = self.run_call(FakeModel(lambda m: "Yes, speaking.", agent), "Enquiry follow-up", "busy", coach=False)
        self.assertEqual(why, "the model's reply could not be read (Ollama error 500)")
        self.assertIsNotNone(rec)

    def test_a_tool_call_written_as_text_becomes_a_real_call(self):
        names = [t["function"]["name"] for t in callkit.TOOLS]
        text, calls = callkit.recover_written_calls('Thanks. logcalloutcome({"outcome_code": "NOT_INTERESTED"})', names)
        self.assertEqual(text, "Thanks.")
        self.assertEqual(calls[0]["function"]["name"], "log_call_outcome")
        text, calls = callkit.recover_written_calls('<tool_call>{"name": "opt_out", "arguments": {"scope": "AI_CALLS"}}</tool_call>', names)
        self.assertEqual((text, calls[0]["function"]["name"]), ("", "opt_out"))
        self.assertEqual(callkit.recover_written_calls('wipe_all({"x": 1})', names)[1], [])

    def test_closing_words_catch_promises_but_not_questions(self):
        for t in ["I will transfer your call now.", "Have a great day!", "A team member will call you back.", "Goodbye."]:
            self.assertTrue(sim.CLOSING_WORDS.search(t), t)
        for t in ["What time suits you?", "Your EMI is 3,250 rupees.", "Could you tell me your full name?"]:
            self.assertFalse(sim.CLOSING_WORDS.search(t), t)


class SourceFilesTest(unittest.TestCase):

    def test_no_control_characters_in_any_source_file(self):
        """A pasted "\\b" can turn into an invisible backspace and silently break a pattern."""
        repo = Path(__file__).resolve().parent.parent
        files = list((repo / "training").glob("*.py")) + list((repo / "backend" / "src").rglob("*.java"))
        bad = [f"{p.relative_to(repo)}:{n}" for p in files
               for n, line in enumerate(p.read_text(encoding="utf-8").splitlines(), 1)
               if any(ch in line for ch in "\x00\x07\x08\x0b\x0c")]
        self.assertEqual(bad, [])


if __name__ == "__main__":
    unittest.main()
