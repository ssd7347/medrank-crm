"""Tests for the training kit: the fake CRM must behave like VoiceTools, and check() must catch bad examples.

    python -m unittest test_callkit
"""

import copy
import json
import random
import unittest

from callkit import (EMPTY_THINK, TOOLS, FieldDef, all_scripts, answer_matches, check, new_call, read_tool_call_tags,
                     render_for_training, spoken_amount, tools_in_system)
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


if __name__ == "__main__":
    unittest.main()
