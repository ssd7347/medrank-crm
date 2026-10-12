"""A simulated phone call between two local models: one is the agent (with the real script and tools, run against
the fake CRM exactly as LlmBrain runs them), the other plays the caller described by a scenario.

Used by generate.py (a large "teacher" model writes training examples) and evaluate.py (our trained model is
tested on calls it has never seen).
"""

from __future__ import annotations

import json
import random
import re

from callkit import CONNECTED, MAX_ROUNDS, TOOLS, FakeCrm, new_call, ollama_chat, record, spoken
from scenarios import Scenario

MAX_CALLER_TURNS = 12

COACH = """
TRAINER NOTES (private, never mention them): the caller in this practice call may behave like this: {brief}
React only to what the caller actually says. Keep every rule above: check identity with verify_identity before any
private detail, answer general questions only from search_faq, one to three short spoken sentences per reply, and
never call get_call_context (its result is already above). When the call is finished, call log_call_outcome (or
opt_out or request_human_handoff when those apply) and then say one short goodbye.
"""

CALLER = """You are {name}, an ordinary person in India. Your mobile rang and you answered: the voice on the line
is an AI assistant from {company}. You only answer and ask as the customer; you never speak for the company. Speak natural Indian
English, like a real phone call: usually one short sentence, sometimes two. Never use lists, labels or stage directions.
Never say you are an AI and never describe what you are doing; just say your words.

Who you are: {who}
What you know if asked: {facts}
How you behave in this call: {brief}
{wrong}
Keep talking until the other side says goodbye; do not end the call yourself."""


def caller_prompt(call: FakeCrm, scenario: Scenario) -> str:
    p = call.person
    if p is None:
        name = random.choice(["Rahul Kumar", "Anitha Reddy", "Joseph Fernandes", "Meena Iyer"])
        who, facts = f"a new customer called {name} ringing {call.company} for the first time.", "only your own name."
    else:
        name = p.full_name
        who = f"{name}, a customer of {call.company}." if scenario.id != "wrong_person" else \
            f"NOT {name}. You are a relative or this is a wrong number; your name is Sunil."
        facts = "; ".join(f"{f.label}: {p.said[f.key]}" for f in call.script.fields if f.verify and f.key in p.said) or "nothing special"
    wrong = ""
    if scenario.wrong_answers and p is not None:
        bad = "; ".join(f"{f.label}: {'4 4 5 5 6 6' if f.type != 'DATE' else '3 March 1979'}" for f in call.script.fields if f.verify)
        times = "every time" if scenario.wrong_answers >= 3 else "the first time only"
        wrong = f"When asked to confirm your details, give these WRONG ones {times}: {bad}."
    return CALLER.format(name=name, company=call.company, who=who, facts=facts, brief=scenario.brief, wrong=wrong)


def clean_caller(text: str) -> str:
    t = re.sub(r"^\s*(\w+\s*)?(caller|customer|me|you)\s*:\s*", "", text.strip(), flags=re.I)
    t = re.sub(r"\(.*?\)|\*.*?\*", "", t).strip().strip('"').strip()
    return spoken(t)[:300]


def simulate(script, scenario: Scenario, rng: random.Random, agent_model: str, caller_model: str, coach: bool,
             agent_temperature: float = 0.5, url: str = "http://localhost:11434") -> tuple[dict | None, str]:
    """Runs one call. Returns (record, "") or (record or None, why it stopped early)."""
    call = new_call(script, rng, known=scenario.known)
    system = call.system_prompt()
    hidden = system + (COACH.format(brief=scenario.brief) if coach else "")
    history = [{"role": "user", "content": CONNECTED}, {"role": "assistant", "content": call.opening()}]
    persona = caller_prompt(call, scenario)
    n = 0
    for _ in range(MAX_CALLER_TURNS):
        # ---- the caller speaks
        flipped = [{"role": "system", "content": persona}]
        for m in history[1:]:
            if m["role"] == "assistant" and m.get("content"):
                flipped.append({"role": "user", "content": m["content"]})
            elif m["role"] == "user":
                flipped.append({"role": "assistant", "content": m["content"]})
        said = clean_caller(ollama_chat(caller_model, flipped, temperature=0.9, url=url, max_tokens=80)["content"])
        if not said:
            return None, "the caller said nothing"
        history.append({"role": "user", "content": said})
        # ---- the agent answers, using tools as often as it needs (as LlmBrain.respond does)
        for _ in range(MAX_ROUNDS):
            r = ollama_chat(agent_model, [{"role": "system", "content": hidden}] + history, TOOLS,
                            temperature=agent_temperature, url=url, max_tokens=300)
            if not r["tool_calls"]:
                text = spoken(r["content"])
                if not text:
                    return None, "the agent gave an empty reply"
                history.append({"role": "assistant", "content": text})
                if not call.closing and re.search(r"(goodbye|bye)", text, re.I):
                    return record(call, [{"role": "system", "content": system}] + history, "generated", scenario.id,
                                  scenario.expect), "the agent said goodbye without recording how the call ended"
                break
            calls = []
            for c in r["tool_calls"]:
                n += 1
                calls.append({"id": f"call_{n}", "type": "function", "function": c["function"]})
            history.append({"role": "assistant", "content": spoken(r["content"]), "tool_calls": calls})
            for c in calls:
                out = call.run(c["function"]["name"], c["function"]["arguments"] if isinstance(c["function"]["arguments"], dict) else {})
                history.append({"role": "tool", "tool_call_id": c["id"], "name": c["function"]["name"], "content": out})
        else:
            return record(call, [{"role": "system", "content": system}] + history, "generated", scenario.id, scenario.expect), \
                "the agent kept using tools without answering"
        if call.closing:
            break
    return record(call, [{"role": "system", "content": system}] + history, "generated", scenario.id, scenario.expect), ""


def transcript(rec: dict) -> str:
    lines = []
    for m in rec["messages"][1:]:
        if m["role"] == "tool":
            lines.append("      = " + m["content"][:160])
        elif m.get("tool_calls"):
            for c in m["tool_calls"]:
                lines.append(f"      > {c['function']['name']} {json.dumps(c['function']['arguments'], ensure_ascii=False)}")
            if m.get("content"):
                lines.append("AGENT:  " + m["content"])
        else:
            lines.append(("AGENT:  " if m["role"] == "assistant" else "CALLER: ") + m["content"])
    return "\n".join(lines)
