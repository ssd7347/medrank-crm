"""Tests for the training kit: the fake CRM must behave like VoiceTools, and check() must catch bad examples.

    python -m unittest test_callkit
"""

import copy
import json
import random
import unittest

from callkit import (CALLBACK_ASKED, EMPTY_THINK, TOOLS, FieldDef, all_scripts, answer_matches, callback_asked, callback_withdrawn, check,
                     identity_habits, new_call, read_tool_call_tags, render_for_training, spoken_amount, tools_in_system, upgrade)
from scenarios import SCENARIOS, build, for_script


def script(name):
    return next(s for s in all_scripts() if s.name == name)


def good(scenario="will_act", name="EMI due reminder", seed=1):
    return build(script(name), next(s for s in SCENARIOS if s.id == scenario), random.Random(seed))


def first_reply(rec):
    """Index of the agent's first spoken reply after the opening line."""
    return next(i for i, m in enumerate(rec["messages"]) if i > 2 and m["role"] == "assistant" and m.get("content"))


class FakeCrmTest(unittest.TestCase):

    def test_private_details_only_after_verification(self):
        call = new_call(script("EMI due reminder"), random.Random(3))
        self.assertEqual(json.loads(call.run("get_contact_details", {}))["error_code"], "VERIFICATION_REQUIRED")
        q = call.verify_fields()[0]
        res = json.loads(call.run("verify_identity", {"full_name": call.person.full_name,
                                                      "answers": {q.key: call.person.said[q.key]}}))
        self.assertTrue(res["data"]["verified"])
        labels = [d["label"] for d in json.loads(call.run("get_contact_details", {}))["data"]["details"]]
        self.assertIn("EMI amount", labels)
        self.assertNotIn("Loan account number", labels)       # NONE fields are never shared

    def test_three_wrong_answers_lock_the_call(self):
        call = new_call(script("Payment reminder"), random.Random(4))
        for left in (2, 1, 0):
            res = json.loads(call.run("verify_identity", {"full_name": call.person.full_name, "answers": {"customer_id": "0000"}}))
            self.assertEqual(res["data"]["attempts_left"], left)
        self.assertEqual(call.outcome, "VERIFICATION_FAILED")
        res = json.loads(call.run("verify_identity", {"full_name": call.person.full_name,
                                                      "answers": {"customer_id": call.person.values["customer_id"]}}))
        self.assertFalse(res["data"]["verified"])

    def test_a_hand_over_is_neither_repeated_nor_overwritten(self):
        # As VoiceAgentIntegrationTest.aHandOverIsNeitherRepeatedNorOverwritten.
        call = new_call(script("Payment reminder"), random.Random(5))
        call.desk_open = False
        mode = lambda reason: json.loads(call.run("request_human_handoff", {"reason_code": reason}))["data"]["handoff_mode"]
        self.assertEqual(mode("CALLER_REQUEST"), "CALLBACK_TASK")
        self.assertEqual(mode("CALLER_REQUEST"), "ALREADY_ARRANGED")
        self.assertEqual(mode("OUT_OF_SCOPE"), "ALREADY_ARRANGED")
        self.assertTrue(json.loads(call.run("log_call_outcome", {"outcome_code": "CALLBACK_REQUESTED"}))["ok"])
        self.assertEqual(call.outcome, "HANDED_OFF")
        self.assertEqual(mode("COMPLAINT_OR_DISPUTE"), "CALLBACK_TASK")
        self.assertEqual(mode("DISPUTE"), "ALREADY_ARRANGED")          # an alias of the same reason
        self.assertEqual(mode("DISTRESS"), "CALLBACK_TASK")
        self.assertEqual((call.outcome, call.handed_over), ("HANDED_OFF", "DISTRESS"))

    def test_the_lock_out_after_a_hand_over_makes_no_second_task(self):
        call = new_call(script("Payment reminder"), random.Random(4))
        call.desk_open = True
        self.assertEqual(json.loads(call.run("request_human_handoff", {"reason_code": "CALLER_REQUEST"}))["data"]["handoff_mode"],
                         "TRANSFER_NOW")
        for _ in range(3):
            res = json.loads(call.run("verify_identity", {"full_name": call.person.full_name, "answers": {"customer_id": "0000"}}))
        self.assertEqual(res["data"]["handoff_mode"], "ALREADY_ARRANGED")
        self.assertEqual(call.outcome, "VERIFICATION_FAILED")

    def test_the_lock_out_is_a_call_back_even_when_the_desk_is_open(self):
        call = new_call(script("Payment reminder"), random.Random(4))
        call.desk_open = True
        for _ in range(3):
            res = json.loads(call.run("verify_identity", {"full_name": call.person.full_name, "answers": {"customer_id": "0000"}}))
        self.assertEqual(res["data"]["handoff_mode"], "CALLBACK_TASK")
        self.assertEqual(call.handed_over, "VERIFICATION_FAILED")

    def test_a_first_name_alone_is_not_a_try(self):
        # As VoiceAgentIntegrationTest: the caller is asked for the full name and keeps all three tries.
        call = new_call(script("Payment reminder"), random.Random(4))
        p = call.person
        right = {"customer_id": p.values["customer_id"]}
        res = json.loads(call.run("verify_identity", {"full_name": p.first_name, "answers": right}))
        self.assertEqual((res["ok"], res["error_code"]), (False, "INVALID_ARGUMENT"))
        self.assertIn("full name", res["error_message"])
        self.assertEqual(call.failures, 0)
        # A wrong two-word name is a real try.
        res = json.loads(call.run("verify_identity", {"full_name": "Sunil Varma", "answers": right}))
        self.assertEqual(res["data"]["attempts_left"], 2)
        res = json.loads(call.run("verify_identity", {"full_name": p.full_name, "answers": right}))
        self.assertTrue(res["data"]["verified"])

    def test_the_full_name_is_asked_for_only_twice(self):
        # As VoiceAgentIntegrationTest: then a person takes over and the call is locked.
        call = new_call(script("Payment reminder"), random.Random(4))
        p = call.person
        right = {"customer_id": p.values["customer_id"]}
        for _ in range(2):
            res = json.loads(call.run("verify_identity", {"full_name": p.first_name, "answers": right}))
            self.assertEqual(res["error_code"], "INVALID_ARGUMENT")
        res = json.loads(call.run("verify_identity", {"full_name": p.first_name, "answers": right}))
        self.assertEqual((res["data"]["verified"], res["data"]["attempts_left"]), (False, 0))
        self.assertEqual(call.outcome, "VERIFICATION_FAILED")
        self.assertTrue(call.settled)
        res = json.loads(call.run("verify_identity", {"full_name": p.full_name, "answers": right}))
        self.assertFalse(res["data"]["verified"])

    def test_a_missing_identity_answer_is_asked_for_without_using_a_try(self):
        # As VoiceAgentIntegrationTest.aMissingIdentityAnswerIsAskedForWithoutUsingATry.
        call = new_call(script("Payment reminder"), random.Random(4))
        p = call.person
        res = json.loads(call.run("verify_identity", {"full_name": p.full_name}))
        self.assertEqual((res["ok"], res["error_code"]), (False, "INVALID_ARGUMENT"))
        self.assertIn("Could I also have your Customer ID", res["error_message"])
        self.assertEqual(call.failures, 0)
        res = json.loads(call.run("verify_identity", {"full_name": p.full_name, "answers": {"customer_id": p.values["customer_id"]}}))
        self.assertEqual((res["data"]["verified"], res["data"]["attempts_left"]), (True, 3))
        # Never answered: asked twice, then a person takes over.
        call = new_call(script("Payment reminder"), random.Random(4))
        for _ in range(2):
            res = json.loads(call.run("verify_identity", {"full_name": call.person.full_name, "answers": {"customer_id": " "}}))
            self.assertEqual(res["error_code"], "INVALID_ARGUMENT")
        res = json.loads(call.run("verify_identity", {"full_name": call.person.full_name}))
        self.assertEqual((res["data"]["verified"], res["data"]["attempts_left"]), (False, 0))
        self.assertEqual(call.outcome, "VERIFICATION_FAILED")

    def test_a_recorded_call_back_is_booked_once(self):
        # As VoiceAgentIntegrationTest.aRecordedCallBackIsBookedOnce.
        call = new_call(script("Payment reminder"), random.Random(4))
        res = json.loads(call.run("log_call_outcome", {"outcome_code": "CALLBACK_REQUESTED", "note": "Busy, call after 5."}))
        self.assertTrue(res["data"]["recorded"])
        self.assertIn("callback_id", res["data"])
        self.assertIn("call you back", res["say"])
        self.assertEqual(call.outcome, "CALLBACK_REQUESTED")
        self.assertNotIn("callback_id", json.loads(call.run("log_call_outcome", {"outcome_code": "CALLBACK_REQUESTED"}))["data"])
        call = new_call(script("Payment reminder"), random.Random(4))
        call.run("schedule_callback", {"preferred_time_text": "after 5"})
        self.assertNotIn("callback_id", json.loads(call.run("log_call_outcome", {"outcome_code": "CALLBACK_REQUESTED"}))["data"])
        # Nor after a hand-over, which a call-back must never overwrite.
        call = new_call(script("Payment reminder"), random.Random(4))
        call.run("request_human_handoff", {"reason_code": "CALLER_REQUEST"})
        self.assertNotIn("callback_id", json.loads(call.run("log_call_outcome", {"outcome_code": "CALLBACK_REQUESTED"}))["data"])
        self.assertEqual(call.outcome, "HANDED_OFF")

    def test_a_call_back_the_caller_asked_for_is_booked_with_the_models_outcome_kept(self):
        # As VoiceAgentIntegrationTest.aCallBackTheCallerAskedForIsBookedWithTheModelsOutcomeKept.
        call = new_call(script("Payment reminder"), random.Random(4))
        res = json.loads(call.run("log_call_outcome", {"outcome_code": "ACKNOWLEDGED", CALLBACK_ASKED: "can you call me back at 4 pm?"}))
        self.assertIn("callback_id", res["data"])
        self.assertIn("call you back", res["say"])
        self.assertEqual(call.outcome, "ACKNOWLEDGED")
        # Booking again in the same call gives the same task.
        again = json.loads(call.run("schedule_callback", {"preferred_time_text": "4 pm"}))
        self.assertEqual(again["data"], {k: res["data"][k] for k in ("callback_id", "confirmed_slot")})

    def test_a_call_back_taken_back_is_heard_in_the_callers_words(self):
        # As AiBrainIntegrationTest.aCallBackTakenBackIsHeardInTheCallersWords.
        withdrawn = {"No need to call me back.": "no need to call me back.",
                     "Okay. Actually, you don't have to call me back, I will pay online.":
                         "actually, you don't have to call me back, i will pay online.",
                     "Call me back at 4. Sorry, no need for a callback, I paid already.":
                         "sorry, no need for a callback, i paid already.",
                     "Please cancel the call back.": "please cancel the call back.",
                     "I don't need anyone to call me back.": "i don't need anyone to call me back.",
                     "Don't bother calling me back.": "don't bother calling me back."}
        for said, sentence in withdrawn.items():
            self.assertEqual(callback_withdrawn(said), sentence, said)
        for said in ("No need to call me back today. Call me tomorrow at 11.", "Don't call me back after 6 pm.",
                     "No need to call me back, just call me tomorrow.", "If you don't call me back I will complain.",
                     "Why don't you call me back later?", "Don't call me back. Actually, call me back tomorrow.",
                     "I don't want to change the date.", "Please call me back at 4.", None):
            self.assertIsNone(callback_withdrawn(said), said)
        self.assertIsNone(callback_asked("Call me back at 4. Actually, no need to call me back."))
        self.assertEqual(callback_asked("No need to call me back today. Call me tomorrow at 11."), "call me tomorrow at 11.")
        logged = {"outcome_code": "WILL_ACT"}
        self.assertIs(upgrade("log_call_outcome", logged, " Call me back at 4. Actually, no need to call me back.")[1], logged)

    def test_a_call_back_asked_for_is_heard_in_the_callers_words(self):
        # As AiBrainIntegrationTest.aCallBackAskedForIsHeardInTheCallersWords.
        asked = {"I'm busy, call me back later.": "i'm busy, call me back later.",
                 "Can we change it to next Tuesday instead?": "can we change it to next tuesday instead?",
                 "Call me in an hour.": "call me in an hour.",
                 "I'm not free now. Call me tomorrow.": "call me tomorrow.",
                 "Don't call me now, call me back later.": "don't call me now, call me back later.",
                 "Don't call me back. Actually, call me back tomorrow.": "actually, call me back tomorrow."}
        for said, sentence in asked.items():
            self.assertEqual(callback_asked(said), sentence, said)
        for said in ("I don't need a callback.", "I don't need a call-back, thank you.", "No need to call me back.",
                     "Please don't reschedule it.", "I don't want to change the date.", "Why did you call me?", "I will pay tomorrow.", None):
            self.assertIsNone(callback_asked(said), said)
        logged = {"outcome_code": "NOT_INTERESTED"}
        self.assertEqual(upgrade("log_call_outcome", logged, " Fine. Not now, call me again tomorrow."),
                         ("log_call_outcome", {"outcome_code": "NOT_INTERESTED", CALLBACK_ASKED: "not now, call me again tomorrow."}))
        self.assertIs(upgrade("log_call_outcome", logged, " I will pay tomorrow.")[1], logged)
        logged_callback = {"outcome_code": "CALLBACK_REQUESTED"}
        self.assertIs(upgrade("log_call_outcome", logged_callback, " Call me back later.")[1], logged_callback)
        self.assertEqual(upgrade("log_call_outcome", logged, " Please stop calling me. Call me back never.")[0], "opt_out")

    def test_answers_match_like_the_backend(self):
        ref = FieldDef("Loan account number", "TEXT", "NONE", True)
        self.assertTrue(answer_matches(ref, "LN-482913", "482913"))
        self.assertTrue(answer_matches(ref, "LN-482913", "2913"))
        self.assertFalse(answer_matches(ref, "LN-482913", "1234"))
        dob = FieldDef("Date of birth", "DATE", "NONE", True)
        self.assertTrue(answer_matches(dob, "1985-03-12", "12th March 1985"))
        self.assertTrue(answer_matches(dob, "1985-03-12", "12/03/1985"))
        self.assertFalse(answer_matches(dob, "1985-03-12", "13/03/1985"))

    def test_training_text_has_no_empty_thinking_block(self):
        class Tok:  # stands in for Qwen3's tokenizer, which adds the block before the last reply
            def apply_chat_template(self, messages, tools, tokenize, add_generation_prompt=False):
                return "<|im_start|>assistant\n" + EMPTY_THINK + "Goodbye.<|im_end|>\n"
        self.assertEqual(render_for_training(Tok(), {"messages": [], "tools": []}), "<|im_start|>assistant\nGoodbye.<|im_end|>\n")

    def test_made_up_amounts_dates_and_deadlines_are_caught(self):
        # As seen in real model-written calls: invented amounts in dollars, dates and deadlines.
        for invented in ["The amount due is $125 and it's due on March 15th.",
                         "Your EMI amount is 250 and it is due soon.",
                         "Our records show your next check-up is due in two weeks.",
                         "Your appointment is on Wednesday."]:
            rec = good()
            rec["messages"][first_reply(rec)]["content"] = invented
            self.assertTrue(any("nothing in the call gave" in p or "nothing in the call used" in p for p in check(rec)), invented)
        # "May I" is an ordinary question, not a month.
        rec = good()
        rec["messages"][first_reply(rec)]["content"] = "May I have your full name, please?"
        self.assertFalse(any("day or month" in p for p in check(rec)))

    def test_a_tool_call_spoken_aloud_or_an_early_goodbye_is_caught(self):
        rec = good()
        rec["messages"][first_reply(rec)]["content"] = 'logcalloutcome("Caller requested human handoff.")'
        self.assertTrue(any("speaks a tool call aloud" in p for p in check(rec)))
        rec = good()
        rec["messages"][first_reply(rec)]["content"] = "Okay, goodbye."
        self.assertTrue(any("goodbye before the call is recorded" in p for p in check(rec)))

    def test_the_caller_never_speaks_twice_in_a_row(self):
        rec = good()
        i = first_reply(rec)
        rec["messages"][i] = {"role": "user", "content": "Hello?"}
        self.assertTrue(any("speaks twice in a row" in p for p in check(rec)))

    def test_amounts_are_spoken_the_indian_way(self):
        self.assertEqual(spoken_amount(4500000), "45,00,000 rupees")
        self.assertEqual(spoken_amount(12500), "12,500 rupees")
        self.assertEqual(spoken_amount(999), "999 rupees")

    def test_tools_travel_in_the_system_message_like_the_training_text(self):
        msgs = tools_in_system([{"role": "system", "content": "You are Neha."}, {"role": "user", "content": "Hi"}], TOOLS)
        self.assertTrue(msgs[0]["content"].startswith("You are Neha.\n\n# Tools\n"))
        self.assertIn("\n" + json.dumps(TOOLS[0], ensure_ascii=False) + "\n", msgs[0]["content"])
        self.assertTrue(msgs[0]["content"].endswith("</tool_call>"))
        self.assertEqual(msgs[1], {"role": "user", "content": "Hi"})

    def test_written_tool_calls_keep_nested_answers(self):
        names = [t["function"]["name"] for t in TOOLS]
        text = ('<tool_call>\n{"name": "verify_identity", "arguments": {"full_name": "Sanjay Chandran", '
                '"answers": {"loan_account_number": "407122"}}}\n</tool_call>\n'
                '<tool_call>\n{"name": "made_up_tool", "arguments": {}}\n</tool_call>')
        rest, calls = read_tool_call_tags(text, names)
        self.assertEqual([c["function"] for c in calls], [{"name": "verify_identity", "arguments":
                         {"full_name": "Sanjay Chandran", "answers": {"loan_account_number": "407122"}}}])
        rest, calls = read_tool_call_tags("Thank you, goodbye.", names)
        self.assertEqual((rest, calls), ("Thank you, goodbye.", []))

    def test_system_prompt_is_built_like_the_live_agent(self):
        call = new_call(script("Incoming calls"), random.Random(5))
        p = call.system_prompt()
        self.assertIn("LIVE PHONE CALL", p)
        self.assertIn("CALL CONTEXT (from get_call_context): {\"ok\":true,\"data\":{\"call_reason\":\"Incoming calls\"", p)
        self.assertNotIn("{{", p)


class CheckTest(unittest.TestCase):

    def test_every_template_scenario_produces_a_clean_call(self):
        for s in all_scripts():
            for sc in for_script(s):
                rec = build(s, sc, random.Random(9))
                self.assertEqual(check(rec), [], f"{s.name} / {sc.id}")

    def test_sharing_a_private_detail_before_verification_is_caught(self):
        rec = good()
        amount = next(v for v in rec["meta"]["private_values"] if v.endswith("rupees"))
        rec["messages"][first_reply(rec)]["content"] = f"Your EMI of {amount} is due soon."
        self.assertTrue(any("before verification" in p for p in check(rec)))

    def test_made_up_amount_is_caught(self):
        rec = good()
        last_text = max(i for i, m in enumerate(rec["messages"]) if m["role"] == "assistant" and not m.get("tool_calls"))
        rec["messages"][last_text]["content"] = "You also owe 999 rupees in fees. Goodbye."
        self.assertTrue(any("no tool returned" in p for p in check(rec)))

    def test_ending_without_recording_the_outcome_is_caught(self):
        rec = good()
        rec["messages"] = [m for m in rec["messages"] if not (m.get("tool_calls") and m["tool_calls"][0]["function"]["name"] == "log_call_outcome")
                           and m.get("name") != "log_call_outcome"]
        problems = check(rec)
        self.assertTrue(any("ends without" in p for p in problems))

    def test_bad_tool_arguments_are_caught(self):
        rec = good()
        for m in rec["messages"]:
            for c in m.get("tool_calls") or []:
                if c["function"]["name"] == "log_call_outcome":
                    c["function"]["arguments"] = {"outcome_code": "PAID_IN_FULL"}
        self.assertTrue(any("is not one of" in p for p in check(rec)))

    def test_long_or_markdown_replies_are_caught(self):
        rec = copy.deepcopy(good())
        rec["messages"][first_reply(rec)]["content"] = "- **Amount**: due. One. Two. Three. Four."
        problems = check(rec)
        self.assertTrue(any("markdown" in p for p in problems))
        self.assertTrue(any("too long" in p for p in problems))

    def test_wrong_outcome_for_the_scenario_is_caught(self):
        rec = good()
        for m in rec["messages"]:
            for c in m.get("tool_calls") or []:
                if c["function"]["name"] == "log_call_outcome":
                    c["function"]["arguments"]["outcome_code"] = "NOT_INTERESTED"
        self.assertTrue(any("expected log_call_outcome" in p for p in check(rec)))


def talk(scenario, *steps):
    """A call for identity_habits(): caller lines (str), agent lines ("AGENT: ..."), identity checks (full_name, result)."""
    msgs = [{"role": "system", "content": "x"}, {"role": "user", "content": "(The call has connected.)"},
            {"role": "assistant", "content": "Hello, am I speaking with Priya?"}]
    for n, s in enumerate(steps):
        if isinstance(s, tuple):
            name, result = s
            msgs += [{"role": "assistant", "content": "", "tool_calls": [{"id": f"c{n}", "type": "function", "function": {
                         "name": "verify_identity", "arguments": {"full_name": name, "answers": {"student_id": "588450"}}}}]},
                     {"role": "tool", "tool_call_id": f"c{n}", "name": "verify_identity", "content": result}]
        elif s.startswith("AGENT: "):
            msgs.append({"role": "assistant", "content": s[7:]})
        else:
            msgs.append({"role": "user", "content": s})
    return {"messages": msgs, "meta": {"scenario": scenario}}


NO = '{"ok":true,"data":{"verified":false,"attempts_left":1}}'
LOCKED = '{"ok":true,"data":{"verified":false,"attempts_left":0,"handoff_mode":"CALLBACK_TASK"}}'
YES = '{"ok":true,"data":{"verified":true,"attempts_left":3}}'


class IdentityHabitsTest(unittest.TestCase):
    """Faults found in the round 4 model-written calls (30 Oct 2026), from real transcripts."""

    def test_a_genuine_customer_locked_out_after_a_single_name_check(self):
        # Real call: "Yes, this is Priya" ... the right student ID, checked with "Priya" alone three times.
        rec = talk("verify_retry", "Yes, this is Priya.", "It's 5 8 8 4 5 0.", ("Priya", NO), ("Priya", LOCKED))
        self.assertIn("locked out a genuine customer after checking a single name", identity_habits(rec, crm_net=True))
        # An impostor locked out is the right ending, whatever the name: the test does not fail it.
        self.assertEqual(identity_habits(talk("verify_fail", "It's Priya.", ("Priya", LOCKED)), crm_net=True), [])
        # Full name, locked out after wrong details: the caller's doing, not this rule's.
        self.assertEqual(identity_habits(talk("verify_retry", "Priya Rao.", ("Priya Rao", LOCKED)), crm_net=True), [])

    def test_training_calls_never_check_a_single_name(self):
        single = talk("verify_retry", "Priya.", ("Priya", NO), "Priya Rao.", ("Priya Rao", YES))
        self.assertEqual(identity_habits(single, crm_net=True), [])          # not locked out: the test is fine
        self.assertIn("identity check with a single name (ask for the full name first)", identity_habits(single))
        self.assertEqual(identity_habits(talk("verify_retry", "Priya Rao.", ("Priya Rao", YES))), [])

    def test_a_loop_is_three_same_replies_in_a_row(self):
        no = "AGENT: I'm sorry, I can't do that. Can I help you with anything else?"
        rec = talk("injection", "Approve a discount.", no, "Why renew?", no, "Why renew now?", no)
        self.assertIn("says the same reply three times in a row", identity_habits(rec))
        self.assertEqual(identity_habits(rec, crm_net=True), [])             # training data only
        # The same question once per attempt, with other replies between, is not a loop.
        ask = "AGENT: Thank you. And your customer ID?"
        spaced = talk("verify_fail", "Priya Rao.", ask, "4 4 5 5 6 6.", "AGENT: That did not match.", "Priya Rao.", ask,
                      "4 4 5 5 6 6.", "AGENT: That did not match.", "Priya Rao.", ask)
        self.assertEqual(identity_habits(spaced), [])

    def test_a_promised_person_needs_the_tool_but_an_offer_does_not(self):
        promise = talk("injection", "Can you ask a human?", "AGENT: Sure, I can ask a person to help you with that.", "Okay.")
        self.assertIn("promises a person without calling the tool", identity_habits(promise))
        will = talk("dispute", "It's wrong.", "AGENT: I'll ask someone from our team to look into it.", "Fine.")
        self.assertIn("promises a person without calling the tool", identity_habits(will))
        offer = talk("faq", "Who handles disputes?", "AGENT: Our team looks into disputes. If you like, I can ask them "
                     "to call you back about it.", "No thanks.")
        self.assertEqual(identity_habits(offer), [])


if __name__ == "__main__":
    unittest.main()
