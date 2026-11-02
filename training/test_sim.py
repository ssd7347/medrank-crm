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

    def test_wrong_details_and_injection_callers_get_exact_words(self):
        rng = random.Random(4)
        impostor = sim.caller_prompt(callkit.new_call(script("Payment reminder"), rng), scenario("verify_fail"))
        self.assertIn('answer with exactly these words: "My customer ID is 4 4 5 5 6 6." Never give', impostor)
        self.assertIn("you do NOT know their real details", impostor)
        call = callkit.new_call(script("Payment reminder"), random.Random(4))
        self.assertNotIn(call.person.said["customer_id"], sim.caller_prompt(call, scenario("verify_fail")))
        retry = sim.caller_prompt(callkit.new_call(script("Payment reminder"), random.Random(5)), scenario("verify_retry"))
        self.assertIn("the first time you are asked", retry)
        trick = sim.caller_prompt(callkit.new_call(script("Payment reminder"), random.Random(6)), scenario("injection"))
        self.assertTrue(any(f'say exactly these words: "{t}"' in trick for t, _ in __import__("scenarios").TRICKS))
        self.assertEqual(sim.caller_version("injection"), 2)
        self.assertEqual(sim.caller_version("busy"), 1)

    def test_hidden_reasoning_is_never_spoken_but_is_counted(self):
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("<think>\nThe caller said no.\n</think>\n\nNo problem. Thank you, goodbye.")
            return tool("log_call_outcome", outcome_code="NOT_INTERESTED")
        rec, why = self.run_call(FakeModel(lambda m: "No, I'm not interested anymore.", agent), "Enquiry follow-up", "not_interested", coach=False)
        self.assertEqual(why, "")
        self.assertEqual(rec["messages"][-1]["content"], "No problem. Thank you, goodbye.")
        self.assertEqual(rec["meta"]["thinking_turns"], 1)

    def test_a_stranger_never_hears_the_reason_for_the_call(self):
        # As AiBrainIntegrationTest: dropped, then asked again with a private note; the caller hears the second answer.
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("Thank you for your time, goodbye.")
            if "PRIVATE NOTE" in msgs[0]["content"]:
                return tool("log_call_outcome", outcome_code="WRONG_PERSON", note="Someone else answered.")
            return say("I'm calling about an overdue EMI payment. Is now a good time?")
        rec, why = self.run_call(FakeModel(lambda m: "This is Sunil.", agent), "Overdue follow-up", "wrong_person", coach=False)
        self.assertEqual(why, "")
        said = " ".join(m.get("content") or "" for m in rec["messages"] if m["role"] == "assistant")
        self.assertNotIn("overdue", said.lower())
        self.assertEqual(rec["meta"]["crm_reason_stops"], [["overdue", "emi", "payment"]])
        self.assertFalse([p for p in callkit.check(rec, crm_net=True) if "names the reason" in p])

    def test_a_model_that_keeps_saying_it_gets_the_fixed_line(self):
        turns = []
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("Thank you for your time, goodbye.")
            turns.append(1)
            if len(turns) > 2:
                return tool("log_call_outcome", outcome_code="WRONG_PERSON", note="Someone else answered.")
            return say("I'm calling about an overdue EMI payment.")
        rec, why = self.run_call(FakeModel(lambda m: "This is Sunil.", agent), "Overdue follow-up", "wrong_person", coach=False)
        self.assertEqual(why, "")
        self.assertEqual(rec["messages"][4]["content"], callkit.REASON_FALLBACK)
        self.assertFalse(callkit.reason_words(callkit.REASON_FALLBACK, ""))

    def test_the_fixed_line_answers_whether_this_is_a_person(self):
        # As AiBrainIntegrationTest.theFixedLineAnswersWhetherThisIsAPerson.
        for said in ("Are you a robot or what?", "Am I talking to a human?", "Is this an AI?"):
            self.assertEqual(callkit.reason_fallback(said), callkit.SPEC["reason_fallback_ai"])
        self.assertEqual(callkit.reason_fallback("Who is this? Why are you calling Harish?"), callkit.REASON_FALLBACK)
        self.assertEqual(callkit.reason_fallback(None), callkit.REASON_FALLBACK)
        self.assertFalse(callkit.reason_words(callkit.SPEC["reason_fallback_ai"], ""))
        turns = []
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("Thank you for your time, goodbye.")
            turns.append(1)
            if len(turns) > 2:
                return tool("log_call_outcome", outcome_code="WRONG_PERSON", note="Someone else answered.")
            return say("I'm calling about an overdue EMI payment.")
        rec, why = self.run_call(FakeModel(lambda m: "Is this a real person? Who are you?", agent), "Overdue follow-up",
                                 "wrong_person", coach=False)
        self.assertEqual(why, "")
        self.assertEqual(rec["messages"][4]["content"], callkit.SPEC["reason_fallback_ai"])

    def test_a_caller_who_says_stop_calling_is_opted_out(self):
        # As AiBrainIntegrationTest.aCallerWhoSaysStopCallingIsOptedOut.
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("Understood. Goodbye.")
            return tool("log_call_outcome", outcome_code="NOT_INTERESTED")
        rec, why = self.run_call(FakeModel(lambda m: "Not interested. Please stop calling me.", agent), "Enquiry follow-up",
                                 "opt_out", coach=False)
        self.assertEqual(why, "")
        res = [m for m in rec["messages"] if m["role"] == "tool"][-1]
        self.assertEqual((res["name"], res["ran"]), ("log_call_outcome", "opt_out"))
        self.assertEqual(rec["meta"]["crm_upgrades"], [{"from": "log_call_outcome", "tool": "opt_out"}])
        self.assertFalse([p for p in check(rec, crm_net=True) if "opt_out" in p])
        self.assertTrue([p for p in check(rec) if "opt_out" in p])
        self.assertEqual(callkit.upgrade("log_call_outcome", {"outcome_code": "NOT_INTERESTED"}, " Not now, call me again tomorrow.")[0],
                         "log_call_outcome")

    def test_a_reply_said_twice_is_not_said_a_third_time(self):
        # As AiBrainIntegrationTest.aReplySaidTwiceIsNotSaidAThirdTime.
        rec, why = self.run_call(FakeModel(lambda m: "Hello? Hello?", lambda msgs, r: say("Sorry, could you say that again?")),
                                 "Enquiry follow-up", "will_act", coach=False)
        self.assertEqual(why, "the CRM handed the call to a person: the agent kept repeating the same reply")
        said = [m["content"] for m in rec["messages"] if m["role"] == "assistant"]
        self.assertEqual(said.count("Sorry, could you say that again?"), 2)

    def test_a_recorded_call_back_counts_as_booked_with_the_crm(self):
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("Of course. Our team will call you back. Thank you, goodbye.")
            return tool("log_call_outcome", outcome_code="CALLBACK_REQUESTED", note="Busy, call later.")
        rec, why = self.run_call(FakeModel(lambda m: "I'm driving, call me later.", agent), "Enquiry follow-up", "busy", coach=False)
        self.assertEqual(why, "")
        self.assertFalse([p for p in check(rec, crm_net=True) if "schedule_callback" in p])
        self.assertTrue([p for p in check(rec) if "schedule_callback" in p])

    def test_the_reason_may_be_named_after_the_check_or_when_the_caller_named_it(self):
        call = callkit.new_call(script("Overdue follow-up"), random.Random(2))
        system = call.system_prompt()
        history = [{"role": "user", "content": callkit.CONNECTED}, {"role": "assistant", "content": call.opening()}]
        self.assertEqual(sim.too_soon("Your EMI is overdue.", history, system, call), ["emi", "overdue"])
        # The identity question has to be asked, though the script's purpose ("overdue") does not unlock anything.
        self.assertEqual(sim.too_soon("May I have your loan account number?", history, system, call), [])
        history.append({"role": "user", "content": "Is this about my EMI?"})
        self.assertEqual(sim.too_soon("Your EMI is overdue.", history, system, call), ["overdue"])
        call.verified = True
        self.assertEqual(sim.too_soon("Your EMI is overdue.", history, system, call), [])

    def test_an_invented_hand_over_reason_is_refused(self):
        call = callkit.new_call(script("Enquiry follow-up"), random.Random(3))
        # WRONG_PERSON is never a hand-over reason; "DISPUTE" is understood (CallerSignals.REASON_ALIASES).
        res = __import__("json").loads(call.run("request_human_handoff", {"reason_code": "WRONG_PERSON", "summary": "x"}))
        self.assertEqual((res["ok"], res["error_code"]), (False, "INVALID_ARGUMENT"))
        self.assertIn("COMPLAINT_OR_DISPUTE", res["error_message"])
        self.assertIsNone(call.outcome)
        self.assertTrue(__import__("json").loads(call.run("request_human_handoff", {"reason_code": "DISPUTE", "summary": "x"}))["ok"])
        self.assertEqual(callkit.handoff_reason("dispute"), "COMPLAINT_OR_DISPUTE")

    def test_a_distressed_callers_call_back_becomes_the_urgent_hand_over(self):
        def agent(msgs, reminded):
            if msgs[-1]["role"] == "tool":
                return say("I'm so sorry. Someone from our team will call you. Take care, goodbye.")
            return tool("schedule_callback", preferred_time_text="later", reason="Wants a call later.")
        rec, why = self.run_call(FakeModel(lambda m: "I lost my job last month, I don't know how I'll manage.", agent),
                                 "Payment reminder", "distress", coach=False)
        self.assertEqual(why, "")
        res = [m for m in rec["messages"] if m["role"] == "tool"][-1]
        self.assertEqual((res["name"], res["ran"]), ("schedule_callback", "request_human_handoff"))
        self.assertEqual(rec["meta"]["crm_upgrades"], [{"from": "schedule_callback", "reason_code": "DISTRESS"}])
        # With the CRM's safety net it is the DISTRESS hand-over the scenario wants; the model alone booked a call-back.
        self.assertNotIn("expected request_human_handoff with {'reason_code': 'DISTRESS'}", check(rec, crm_net=True))
        self.assertTrue(any("request_human_handoff" in p for p in check(rec)))

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
