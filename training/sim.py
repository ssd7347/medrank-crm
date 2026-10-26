"""A simulated phone call between two local models: one is the agent (with the real script and tools, run against
the fake CRM exactly as LlmBrain runs them), the other plays the caller described by a scenario.

Used by generate.py (a large "teacher" model writes training examples) and evaluate.py (our trained model is
tested on calls it has never seen).
"""

from __future__ import annotations

import json
import random
import re
import urllib.error

from callkit import CONNECTED, MAX_ROUNDS, TOOLS, FakeCrm, new_call, ollama_chat, record, spoken, ungrounded
from scenarios import Scenario

MAX_CALLER_TURNS = 12

REMIND_CLOSE = """
REMINDER (private): you were about to promise an action or end the call without using the tool for it. Saying it
does nothing: call the tool now. A person wanted, a complaint or distress: request_human_handoff. A call later:
schedule_callback. No more calls: opt_out. Otherwise, when the call is done: log_call_outcome with the right
outcome_code. Only after the tool has answered, say one short goodbye.
"""
# Words that end a call or promise an action; said with no tool behind them, the call is never recorded.
CLOSING_WORDS = re.compile(r"\b(good\s?bye|bye|have a (great|good|nice|lovely|wonderful) day|take care|"
                           r"transfer|connect(ing)? you|call you back|call back|someone will (call|contact)|"
                           r"(team|person) will (call|contact|reach))", re.I)

COACH = """
TRAINER NOTES (private, never mention them): the caller in this practice call may behave like this: {brief}
React only to what the caller actually says. Keep every rule above: check identity with verify_identity before any
private detail, answer general questions only from search_faq, one to three short spoken sentences per reply, and
never call get_call_context (its result is already above). Never promise an action (a transfer, a call-back,
noting a date) without calling its tool in that same reply: saying it does nothing. When the call is finished,
call log_call_outcome (or opt_out or request_human_handoff when those apply) and then say one short goodbye.
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


def unheard(said: str, history: list[dict], system: str) -> list[str]:
    """FactGuard.unheard(): the facts in a reply that the script, the caller and the tools never gave."""
    heard = " ".join([system] + [m.get("content") or "" for m in history if m["role"] != "assistant"]).lower()
    facts = [p[p.rindex("(") + 1:-1] for p in ungrounded(said.lower(), heard)]
    return list(dict.fromkeys(facts))


def fact_note(facts: list[str]) -> str:
    """FactGuard.reminder(), word for word."""
    return ("\n\nPRIVATE NOTE (never mention it): your last reply stated " + ", ".join(facts)
            + ", which no tool returned and the caller did not say. Never guess a date, time, amount or number."
            + " Use only what the tools or the caller gave. If you do not have it, say you cannot check right now"
            + " and offer a call-back.")


def clean_caller(text: str) -> str:
    t = re.sub(r"^\s*(\w+\s*)?(caller|customer|me|you)\s*:\s*", "", text.strip(), flags=re.I)
    t = re.sub(r"\(.*?\)|\*.*?\*", "", t).strip().strip('"').strip()
    return spoken(t)[:300]


def simulate(script, scenario: Scenario, rng: random.Random, agent_model: str, caller_model: str, coach: bool,
             agent_temperature: float = 0.5, url: str = "http://localhost:11434",
             agent_think: bool | None = None) -> tuple[dict | None, str]:
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
        said = ""
        for attempt in range(3):
            # A reply that is only a stage direction ("*hangs up*") cleans to nothing: ask again for spoken words.
            said = clean_caller(ollama_chat(caller_model, flipped + ([{"role": "user", "content":
                    "(Reply with the words you say out loud, nothing else.)"}] if attempt else []),
                    temperature=0.9, url=url, max_tokens=80)["content"])
            if said:
                break
        if not said:
            return None, "the caller said nothing"
        history.append({"role": "user", "content": said})
        # ---- the agent answers, using tools as often as it needs (as LlmBrain.respond does)
        reminder = ""
        facts_note = ""          # as LlmBrain.respond: set once a reply invented a fact, for this caller turn
        for _ in range(MAX_ROUNDS):
            try:
                r = ollama_chat(agent_model, [{"role": "system", "content": hidden + reminder + facts_note}] + history, TOOLS,
                                temperature=agent_temperature, url=url, max_tokens=300, think=agent_think)
            except urllib.error.HTTPError as e:
                # Ollama could not read the model's reply (usually a broken tool call): the model's mistake, so it
                # counts against this call. On a real call the CRM hands over to a person in this case.
                return record(call, [{"role": "system", "content": system}] + history, "generated", scenario.id,
                              scenario.expect), f"the model's reply could not be read (Ollama error {e.code})"
            if not r["tool_calls"]:
                text = spoken(r["content"])
                if not text:
                    return None, "the agent gave an empty reply"
                made = unheard(text, history, system)
                if made:
                    # The CRM's FactGuard: the caller never hears it; one more try with a note, then a person.
                    call.corrections.append(made)
                    if not facts_note:
                        facts_note = fact_note(made)
                        continue
                    return record(call, [{"role": "system", "content": system}] + history, "generated", scenario.id,
                                  scenario.expect), "the CRM handed the call to a person: the agent kept stating facts it was not given"
                if not call.closing and coach and not reminder and CLOSING_WORDS.search(text):
                    # Writing training calls: drop this reply and ask again with a private reminder, so only the
                    # corrected turn is kept. (Never while testing: there the mistake must count.)
                    reminder = REMIND_CLOSE
                    continue
                goodbye = re.search(r"\b(goodbye|bye)\b", text, re.I)
                if not call.closing and not call.settled and goodbye:
                    history.append({"role": "assistant", "content": text})
                    return record(call, [{"role": "system", "content": system}] + history, "generated", scenario.id,
                                  scenario.expect), "the agent said goodbye without recording how the call ended"
                history.append({"role": "assistant", "content": text})
                if call.settled and goodbye:
                    # As LlmBrain.respond: the call-back or the lock-out already recorded the outcome, so the call ends.
                    call.over = True
                break
            calls = []
            for c in r["tool_calls"]:
                n += 1
                calls.append({"id": f"call_{n}", "type": "function", "function": c["function"]})
            alongside = spoken(r["content"])
            # As LlmBrain: words said alongside a tool call are spoken (and kept) only if they invent nothing.
            history.append({"role": "assistant", "content": alongside if not unheard(alongside, history, system) else "",
                            "tool_calls": calls})
            for c in calls:
                out = call.run(c["function"]["name"], c["function"]["arguments"] if isinstance(c["function"]["arguments"], dict) else {})
                history.append({"role": "tool", "tool_call_id": c["id"], "name": c["function"]["name"], "content": out})
        else:
            return record(call, [{"role": "system", "content": system}] + history, "generated", scenario.id, scenario.expect), \
                "the agent kept using tools without answering"
        if call.closing or call.over:
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
