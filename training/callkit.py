"""Shared pieces of the training kit.

Everything here mirrors the live agent in the CRM backend so that the conversations we train on look exactly
like real calls:

* the system prompt is built the way LlmBrain builds it (script + VOICE_RULES + CALL CONTEXT),
* the tools and their schemas come from spec/agent-spec.json, which TrainingSpecTest keeps identical to the code,
* FakeCrm answers every tool the way VoiceTools does (same rules, same wording, same JSON shape),
* check() rejects any conversation that breaks the rules a real call must keep.

Only the Python standard library is used, so nothing needs installing to make data.
"""

from __future__ import annotations

import datetime as dt
import json
import random
import re
import urllib.request
from dataclasses import dataclass, field
from pathlib import Path

HERE = Path(__file__).resolve().parent
REPO = HERE.parent
PACKS = REPO / "backend" / "src" / "main" / "resources" / "packs"
DATA = HERE / "data"

SPEC = json.loads((HERE / "spec" / "agent-spec.json").read_text(encoding="utf-8"))
TOOLS: list[dict] = SPEC["tools"]
TOOL_NAMES = {t["function"]["name"] for t in TOOLS}
VOICE_RULES: str = SPEC["voice_rules"]
CLOSING_TOOLS = set(SPEC["closing_tools"])
MAX_ROUNDS: int = SPEC["max_tool_rounds"]
TEMPLATE = (PACKS / "script-template.txt").read_text(encoding="utf-8")

CONNECTED = "(The call has connected.)"
TODAY = dt.date(2026, 10, 12)


# --------------------------------------------------------------------------------------------- scripts

@dataclass
class FieldDef:
    label: str
    type: str          # TEXT, DATE, AMOUNT, NUMBER
    access: str        # NONE, AFTER_VERIFY, ANYONE
    verify: bool

    @property
    def key(self) -> str:
        return re.sub(r"^_+|_+$", "", re.sub(r"[^a-z0-9]+", "_", self.label.lower()))[:40]


@dataclass
class Script:
    name: str
    kind: str          # OUTBOUND or INBOUND
    purpose: str
    opening: str
    tag: str           # payment, appointment, enquiry, feedback, inbound
    industry: str
    fields: list[FieldDef]
    faqs: list[dict]

    @property
    def prompt(self) -> str:
        return TEMPLATE.replace("{{purpose}}", self.purpose).strip()


OPENING = "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}. This call may be recorded. Am I speaking with {{first_name}}?"

GENERIC_FAQS = [
    {"question": "What are your opening hours?", "answer": "We are open from 9 am to 6 pm, Monday to Saturday.", "keywords": "open, hours, timing, time, close"},
    {"question": "Where are you located?", "answer": "Our office is at 14 Anna Salai, Chennai, near the Gemini flyover.", "keywords": "where, address, location, office, located"},
    {"question": "How can I pay?", "answer": "You can pay by UPI, through the payment link we send by SMS, or at our office.", "keywords": "pay, payment, upi, link, how"},
]

# The five starter scripts every installation has (V1__schema.sql).
GENERIC = [
    ("Payment reminder", "OUTBOUND", "payment",
     "Remind the customer about a payment that is due. Use get_contact_details for the amount and date. If they promise a date, log WILL_ACT with that date in the note.",
     OPENING),
    ("Appointment reminder", "OUTBOUND", "appointment",
     "Remind the customer of their upcoming appointment and confirm they can attend. If they cannot, offer a call-back to rebook.",
     "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}. This call may be recorded. Am I speaking with {{first_name}}? I am calling about your upcoming appointment."),
    ("Enquiry follow-up", "OUTBOUND", "enquiry",
     "Follow up on the enquiry the customer made. Find out whether they are still interested. If yes, log INTERESTED and offer a call-back from the team.",
     "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}. This call may be recorded. Am I speaking with {{first_name}}? You recently enquired with us."),
    ("Feedback survey", "OUTBOUND", "feedback",
     "Ask the customer how satisfied they were, on a scale of 1 to 5, and whether they have any comments. Log the score and comments in the note.",
     "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}. This call may be recorded. Do you have one minute to share feedback?"),
    ("Incoming calls", "INBOUND", "inbound",
     "Someone has rung the company. Help with general questions from search_faq and their own account details after verification. Otherwise arrange a call-back.",
     "Hello, you have reached {{company_name}}. I am {{agent_name}}, an AI assistant, and this call may be recorded. How can I help you today?"),
]

GENERIC_FIELDS = {
    "payment": [FieldDef("Customer ID", "TEXT", "NONE", True), FieldDef("Amount due", "AMOUNT", "AFTER_VERIFY", False),
                FieldDef("Due date", "DATE", "AFTER_VERIFY", False)],
    "appointment": [FieldDef("Customer ID", "TEXT", "NONE", True), FieldDef("Appointment date", "DATE", "AFTER_VERIFY", False),
                    FieldDef("Appointment time", "TEXT", "AFTER_VERIFY", False)],
    "enquiry": [FieldDef("Enquired about", "TEXT", "ANYONE", False)],
    "feedback": [],
    "inbound": [FieldDef("Customer ID", "TEXT", "NONE", True), FieldDef("Plan", "TEXT", "ANYONE", False),
                FieldDef("Amount due", "AMOUNT", "AFTER_VERIFY", False), FieldDef("Due date", "DATE", "AFTER_VERIFY", False)],
}

PACK_TAGS = {
    "Clinic appointment reminder": "appointment", "Check-up recall": "appointment",
    "EMI due reminder": "payment", "Overdue follow-up": "payment",
    "Admission enquiry follow-up": "enquiry", "Fee reminder": "payment",
    "Policy renewal reminder": "payment",
    "Property enquiry follow-up": "enquiry", "Site visit reminder": "appointment",
}

PLACEHOLDERS = {
    "UPI ID / payment link / branch / app": "UPI, the payment link we sent by SMS, or any of our branches",
    "payment link / app / branch": "the payment link we sent by SMS, our app, or any branch",
    "time": "24 hours", "days": "Monday to Saturday", "opening time": "9 am", "closing time": "7 pm",
    "address": "22 Gandhi Road, T Nagar, Chennai", "full address": "22 Gandhi Road, T Nagar, Chennai 600017",
    "landmark": "the Pondy Bazaar signal", "amount": "500 rupees", "date": "the 1st of next month",
    "number": "98400 12345", "banks": "SBI, HDFC and ICICI", "course": "the foundation course",
    "courses": "NEET coaching, JEE coaching and the foundation course", "Project name": "Green Meadows",
    "1/2/3 BHK, plot sizes": "2 and 3 BHK homes", "in instalments / at once": "in instalments",
    "online classes are also available": "online classes are also available",
}


def fill(text: str) -> str:
    """Replaces the [placeholders] a customer fills in with believable values."""
    return re.sub(r"\[([^\]]+)\]", lambda m: PLACEHOLDERS.get(m.group(1), m.group(1).split("/")[0].strip()), text)


def all_scripts() -> list[Script]:
    out = [Script(n, k, p, o, tag, "general", GENERIC_FIELDS[tag], GENERIC_FAQS) for n, k, tag, p, o in GENERIC]
    for f in sorted(PACKS.glob("*.json")):
        pack = json.loads(f.read_text(encoding="utf-8"))
        fields = [FieldDef(x["label"], x["type"], x["agentAccess"], x["verifyWith"]) for x in pack["fields"]]
        faqs = [{**q, "answer": fill(q["answer"])} for q in pack["faqs"]] + GENERIC_FAQS[:2]
        for s in pack["scripts"]:
            out.append(Script(s["name"], s["kind"], s["purpose"], s["opening"], PACK_TAGS.get(s["name"], "payment"),
                              pack["id"], fields, faqs))
    return out


# --------------------------------------------------------------------------------------------- people and values

FIRST = ["Priya", "Rahul", "Anitha", "Karthik", "Divya", "Suresh", "Lakshmi", "Arun", "Meena", "Vijay", "Deepa",
         "Ramesh", "Kavitha", "Sanjay", "Revathi", "Manoj", "Sneha", "Ganesh", "Pooja", "Harish", "Nisha", "Ashok",
         "Fathima", "Joseph", "Ayesha", "Imran", "Gurpreet", "Rohan", "Shalini", "Venkat"]
LAST = ["Sharma", "Kumar", "Iyer", "Reddy", "Nair", "Patel", "Menon", "Rao", "Krishnan", "Pillai", "Singh", "Das",
        "Gupta", "Joshi", "Raman", "Subramanian", "Khan", "Fernandes", "Chandran", "Bose"]
COMPANIES = {
    "general": ["Sunrise Services", "Kaveri Enterprises", "Bluewave Solutions", "Lotus Retail"],
    "clinic": ["Sunrise Clinic", "Apollo Family Care", "Lotus Dental", "CarePlus Hospital"],
    "finance": ["Kaveri Finance", "Trust Capital", "Shree Loans", "Metro Credit"],
    "education": ["Bright Future Academy", "Vidya Coaching Centre", "Excel Institute"],
    "insurance": ["Shield Insurance", "SafeLife Assurance", "Guardian Insurance"],
    "real_estate": ["Greenfield Realty", "Skyline Homes", "Prestige Properties"],
}
AGENTS = ["Asha", "Maya", "Priya", "Kavya", "Arjun", "Ravi", "Diya", "Neha"]
TEXT_VALUES = {
    "doctor": ["Dr. Meera Rao", "Dr. Suresh Babu", "Dr. Anjali Menon"],
    "department": ["Cardiology", "Dental", "General Medicine", "Orthopaedics"],
    "appointment_time": ["10:30 am", "4:15 pm", "11 am", "6 pm"],
    "course": ["NEET coaching", "JEE coaching", "the foundation course"],
    "policy_type": ["health insurance", "term life insurance", "motor insurance"],
    "project": ["Green Meadows", "Lakeview Towers", "Palm Grove Villas"],
    "preferred_location": ["OMR", "Velachery", "Anna Nagar", "Porur"],
    "property_type": ["2 BHK apartment", "3 BHK apartment", "villa plot"],
    "parent_name": ["Mr. Raman", "Mrs. Latha"],
    "enquired_about": ["a home loan", "the premium plan", "a new connection", "the annual membership"],
    "plan": ["the Silver plan", "the Gold plan", "the Basic plan"],
}


def spoken_date(d: dt.date) -> str:
    return f"{d:%A} {d.day} {d:%B} {d.year}"


def spoken_amount(n: int) -> str:
    s = str(n)
    if len(s) > 3:
        head, tail = s[:-3], s[-3:]
        head = ",".join(re.findall(r"\d{1,2}", head[::-1]))[::-1]
        s = head + "," + tail
    return s + " rupees"


def spoken_time(t: dt.datetime) -> str:
    hour = t.hour % 12 or 12
    return f"{t:%A} {t.day} {t:%B}, {hour}:{t.minute:02d} {'AM' if t.hour < 12 else 'PM'}"


@dataclass
class Person:
    full_name: str
    values: dict[str, str]        # field key -> stored value (ISO dates, plain numbers)
    said: dict[str, str]          # field key -> how the person would say it on the phone

    @property
    def first_name(self) -> str:
        return self.full_name.split()[0]


def make_person(script: Script, rng: random.Random) -> Person:
    name = f"{rng.choice(FIRST)} {rng.choice(LAST)}"
    values, said = {}, {}
    for f in script.fields:
        k = f.key
        if f.type == "DATE":
            if "birth" in k:
                d = dt.date(rng.randint(1962, 2004), rng.randint(1, 12), rng.randint(1, 28))
                said[k] = rng.choice([f"{d.day} {d:%B} {d.year}", f"{d.day:02d}/{d.month:02d}/{d.year}", f"{d:%B} {d.day}, {d.year}"])
            else:
                # Each date different: a public date equal to a private one would look like a privacy leak.
                taken = set(values.values())
                d = TODAY + dt.timedelta(days=rng.randint(2, 20))
                while d.isoformat() in taken:
                    d += dt.timedelta(days=1)
                said[k] = spoken_date(d)
            values[k] = d.isoformat()
        elif f.type == "AMOUNT":
            n = rng.choice([1500, 2400, 3250, 4999, 7800, 12500, 18750, 23000, 45000]) if "budget" not in k else rng.choice([4500000, 6200000, 8500000])
            values[k], said[k] = str(n), spoken_amount(n)
        elif f.type == "NUMBER":
            n = rng.randint(3, 45)
            values[k], said[k] = str(n), str(n)
        elif k in TEXT_VALUES:
            values[k] = said[k] = rng.choice(TEXT_VALUES[k])
        else:  # reference numbers: customer, loan, policy, patient, student and enquiry IDs
            prefix = {"loan": "LN", "policy": "POL", "patient": "PT", "student": "ST", "enquiry": "ENQ"}.get(k.split("_")[0], "CUST")
            digits = str(rng.randint(100000, 999999))
            values[k] = f"{prefix}-{digits}"
            said[k] = rng.choice([digits, f"{prefix} {digits}", " ".join(digits)])
    return Person(name, values, said)


# --------------------------------------------------------------------------------------------- the fake CRM

def jdump(o) -> str:
    """Compact JSON, as Jackson writes it in the backend."""
    return json.dumps(o, ensure_ascii=False, separators=(",", ":"))


def result_json(ok: bool, data: dict | None = None, say: str | None = None, code: str | None = None, msg: str | None = None) -> str:
    """The JSON LlmBrain hands the model for a tool result."""
    m: dict = {"ok": ok}
    if data:
        m["data"] = data
    if say is not None:
        m["say"] = say
    if not ok:
        m["error_code"], m["error_message"] = code, msg
    return jdump(m)


def _words(s: str | None) -> set[str]:
    return {w for w in re.split(r"[^\w]+", (s or "").lower()) if len(w) > 1}


def _squash(s: str | None) -> str:
    return re.sub(r"[^\w]", "", (s or "").lower()).replace("_", "")


def _date(said: str) -> str | None:
    s = re.sub(r"(\d)(st|nd|rd|th)\b", r"\1", said.strip()).replace(",", "")
    for fmt in ("%Y-%m-%d", "%d/%m/%Y", "%d-%m-%Y", "%d %B %Y", "%d %b %Y", "%B %d %Y", "%b %d %Y", "%A %d %B %Y"):
        try:
            return dt.datetime.strptime(s, fmt).date().isoformat()
        except ValueError:
            pass
    return None


def answer_matches(f: FieldDef, stored: str | None, said: str | None) -> bool:
    if not stored or not said or not said.strip():
        return False
    if f.type == "DATE":
        return _date(said) == stored
    if f.type in ("NUMBER", "AMOUNT"):
        return re.sub(r"[^\d.]", "", said) == stored
    s, v = _squash(said), _squash(stored)
    if s == v:
        return True
    sd, vd = re.sub(r"\D", "", said), re.sub(r"\D", "", stored)
    if len(vd) >= 4 and sd == vd:
        return True
    return len(v) >= 6 and len(s) == 4 and v.endswith(s)


@dataclass
class FakeCrm:
    """One call's view of the CRM: the contact, the company's answers and what has happened so far."""

    script: Script
    person: Person | None          # None: an unknown caller rang in
    company: str
    agent: str
    rng: random.Random
    desk_open: bool = False
    verified: bool = False
    failures: int = 0
    outcome: str | None = None
    closing: bool = False
    settled: bool = False          # a call-back or the identity lock-out already recorded the outcome (settles_the_call)
    over: bool = False
    tool_log: list[tuple[str, dict, dict]] = field(default_factory=list)   # (name, args, parsed result)
    corrections: list[list[str]] = field(default_factory=list)   # facts the CRM's FactGuard stopped (see sim.py)
    thinking: int = 0              # replies with hidden reasoning (removed before speaking, but they cost time)

    # ---- context and prompt

    def private_fields(self) -> list[FieldDef]:
        return [f for f in self.script.fields if f.access == "AFTER_VERIFY" and self.person and self.person.values.get(f.key)]

    def verify_fields(self) -> list[FieldDef]:
        return [f for f in self.script.fields if f.verify and self.person and self.person.values.get(f.key)]

    def context(self) -> dict:
        p = self.person
        data = {
            "call_reason": self.script.name,
            "direction": self.script.kind,
            "company_name": self.company,
            "first_name": p.first_name if p else None,
            "known_contact": p is not None,
            "verified": self.verified,
            "verification_required": bool(p and not self.verified and self.private_fields()),
            "verification_questions": ([{"ask_for": "their full name", "key": "full_name"}]
                                       + [{"ask_for": f.label, "key": f.key} for f in self.verify_fields()]) if p else [],
            "recording": True,
        }
        return {"ok": True, "data": data}

    def variables(self) -> dict[str, str]:
        return {"company_name": self.company, "agent_name": self.agent, "first_name": self.person.first_name if self.person else ""}

    def render(self, text: str) -> str:
        for k, v in self.variables().items():
            text = text.replace("{{" + k + "}}", v)
        return re.sub(r"\{\{[a-z_]+\}\}", "", text)

    def system_prompt(self) -> str:
        return self.render(self.script.prompt) + VOICE_RULES + "\nCALL CONTEXT (from get_call_context): " + jdump(self.context()) + "\n"

    def opening(self) -> str:
        return self.render(self.script.opening)

    # ---- tools

    def run(self, name: str, args: dict) -> str:
        handler = getattr(self, "t_" + name, None)
        if handler is None or name not in TOOL_NAMES:
            out = result_json(False, code="UNKNOWN_TOOL", msg="There is no tool called " + name + ".")
        else:
            out = handler(args or {})
        parsed = json.loads(out)
        self.tool_log.append((name, args or {}, parsed))
        if parsed["ok"] and name in CLOSING_TOOLS:
            self.closing = True
        if settles_the_call(name, parsed):
            self.settled = True
        return out

    def _callback_slot(self, minutes: int) -> str:
        base = dt.datetime(2026, 10, 12, 11, 0) + dt.timedelta(days=self.rng.randint(0, 2), minutes=minutes)
        return spoken_time(base)

    def t_get_call_context(self, args):
        c = self.context()
        return result_json(True, c["data"])

    def t_verify_identity(self, args):
        if not self.person:
            return result_json(False, code="NOT_AVAILABLE", msg="This caller is not in our records, so identity cannot be checked. Offer a call-back.")
        if self.verified:
            return result_json(True, {"verified": True, "attempts_left": 3 - self.failures})
        if self.failures >= 3:
            return result_json(True, {"verified": False, "attempts_left": 0}, "I could not verify the details, so a member of our team will help you.")
        name_ok = _words(self.person.full_name) <= _words(str(args.get("full_name") or ""))
        answers = {str(k).lower(): str(v) for k, v in args.items() if isinstance(v, str)}
        if isinstance(args.get("answers"), dict):
            answers.update({str(k).lower(): str(v) for k, v in args["answers"].items() if v is not None})
        checks = self.verify_fields()
        extra_ok = not checks or any(answer_matches(f, self.person.values.get(f.key), answers.get(f.key, answers.get(f.label.lower()))) for f in checks)
        if name_ok and extra_ok:
            self.verified = True
            return result_json(True, {"verified": True, "attempts_left": 3 - self.failures}, "Thank you, that's verified.")
        self.failures += 1
        left = 3 - self.failures
        if left <= 0:
            self.outcome = "VERIFICATION_FAILED"
            msg = "A member of our team will call you back by " + self._callback_slot(120) + "."
            return result_json(True, {"verified": False, "attempts_left": 0, "handoff_mode": "CALLBACK_TASK"}, "I could not verify the details. " + msg)
        return result_json(True, {"verified": False, "attempts_left": left}, "That did not match our records. Could you check and tell me again?")

    def visible(self) -> list[tuple[FieldDef, str]]:
        if not self.person:
            return []
        out = []
        for f in self.script.fields:
            v = self.person.values.get(f.key)
            if v and (f.access == "ANYONE" or (f.access == "AFTER_VERIFY" and self.verified)):
                out.append((f, self.person.said[f.key] if f.type != "DATE" else spoken_date(dt.date.fromisoformat(v))))
        return out

    def t_get_contact_details(self, args):
        if not self.person:
            return result_json(False, code="NOT_AVAILABLE", msg="This caller is not in our records. Offer a call-back.")
        rows = self.visible()
        more = not self.verified and bool(self.private_fields())
        if not rows:
            if more:
                return result_json(False, code="VERIFICATION_REQUIRED", msg="Ask the caller to verify identity first.")
            return result_json(False, code="NOT_AVAILABLE", msg="There are no details on record to share. Offer a call-back.")
        hint = "; ".join(f"{f.label}: {v}" for f, v in rows[:3]) + "."
        return result_json(True, {"details": [{"key": f.key, "label": f.label, "value": v} for f, v in rows],
                                  "more_after_verification": more}, hint)

    def t_search_faq(self, args):
        q = str(args.get("query") or "").strip()
        if not q:
            return result_json(False, code="INVALID_ARGUMENT", msg="A question is needed.")
        words = _words(q)
        scored = []
        for f in self.script.faqs:
            keys = _words(f["question"] + " " + f.get("keywords", "").replace(",", " ")) - STOP
            score = len((words - STOP) & keys)
            if score:
                scored.append((score, f))
        if not scored:
            return result_json(False, code="NOT_AVAILABLE", msg="Nothing in the knowledge base answers this. Offer a call-back from the team.")
        scored.sort(key=lambda x: -x[0])
        found = [f for _, f in scored[:3]]
        return result_json(True, {"answers": [{"question": f["question"], "answer": f["answer"]} for f in found]}, found[0]["answer"])

    def t_schedule_callback(self, args):
        if self.outcome is None:
            self.outcome = "CALLBACK_REQUESTED"
        slot = self._callback_slot(120)
        return result_json(True, {"callback_id": self.rng.randint(100, 999), "confirmed_slot": slot},
                           "A member of our team will call you back by " + slot + ".")

    def t_request_human_handoff(self, args):
        reason = str(args.get("reason_code") or "").upper()
        if reason not in SPEC["handoff_reasons"]:
            # VoiceTools.handoff: an invented reason is refused and the model asks again; nothing is handed over.
            return result_json(False, code="INVALID_ARGUMENT", msg="reason_code must be one of: " + ", ".join(SPEC["handoff_reasons"])
                               + ". A wrong person is not a hand-over: use log_call_outcome with WRONG_PERSON.")
        if reason == "VERIFICATION_FAILED":
            self.outcome = "VERIFICATION_FAILED"
        elif self.outcome not in ("OPTED_OUT", "VERIFICATION_FAILED"):
            self.outcome = "HANDED_OFF"
        if self.desk_open:
            msg, mode = "I am connecting you to a member of our team now. Please stay on the line.", "TRANSFER_NOW"
        else:
            minutes = 15 if reason == "DISTRESS" else 30 if reason == "COMPLAINT_OR_DISPUTE" else 120
            msg, mode = "A member of our team will call you back by " + self._callback_slot(minutes) + ".", "CALLBACK_TASK"
        return result_json(True, {"handoff_mode": mode, "message": msg}, msg)

    def t_log_call_outcome(self, args):
        code = str(args.get("outcome_code") or "").upper()
        if code not in SPEC["outcomes"]:
            return result_json(False, code="INVALID_ARGUMENT", msg="Unknown outcome code.")
        if self.outcome not in ("OPTED_OUT", "VERIFICATION_FAILED"):
            self.outcome = code
        return result_json(True, {"recorded": True})

    def t_opt_out(self, args):
        scope = "ALL_CALLS" if str(args.get("scope")).upper() == "ALL_CALLS" else "AI_CALLS"
        self.outcome = "OPTED_OUT"
        return result_json(True, {"confirmed": True, "scope": scope}, "I have noted that. You will not get these calls again.")


STOP = {"the", "is", "are", "what", "how", "can", "do", "you", "your", "my", "me", "to", "of", "and", "for", "it", "in",
        "on", "a", "an", "we", "our", "us", "be", "will", "about", "there", "any", "this", "that", "please", "tell", "if"}


def new_call(script: Script, rng: random.Random, known: bool = True) -> FakeCrm:
    person = make_person(script, rng) if known else None
    return FakeCrm(script, person, rng.choice(COMPANIES[script.industry]), rng.choice(AGENTS), rng, desk_open=rng.random() < 0.3)


# --------------------------------------------------------------------------------------------- training text

# Qwen3's Hugging Face template puts an empty thinking block before the last reply. Ollama's template (what runs on
# real calls) has none, so training must not either: otherwise the model learns to say "<think></think>".
EMPTY_THINK = "<think>\n\n</think>\n\n"


def render_for_training(tok, row: dict, generation_prompt: bool = False) -> str:
    """One call as the model reads it on a real call: the tokenizer's chat format, without the empty thinking block."""
    text = tok.apply_chat_template(row["messages"], tools=row["tools"], tokenize=False,
                                   add_generation_prompt=generation_prompt)
    return text.replace(EMPTY_THINK, "")


# --------------------------------------------------------------------------------------------- spoken text

def spoken(text: str | None) -> str:
    """LlmBrain.spoken(): what the model wrote, made fit to be spoken."""
    if not text:
        return ""
    # A "thinking" model's hidden reasoning (<think>...</think>, or cut off unfinished) is never read aloud.
    t = re.sub(r"(?s)<think>.*?(</think>|$)", "", text)
    t = re.sub(r"(?m)^\s*([-*•]|\d+[.)])\s+", "", t)
    t = re.sub(r"[*_#`>|]", "", t)
    t = re.sub(r"\s*\n+\s*", " ", t)
    return re.sub(r"\s{2,}", " ", t).strip()[:600]


# --------------------------------------------------------------------------------------------- checks

EMOJI = re.compile("[\U0001F300-\U0001FAFF☀-➿]")


def sentences(text: str) -> int:
    return len([s for s in re.split(r"(?<=[.!?])\s+", text.strip()) if re.search(r"\w", s)])


WEEKDAYS = ("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
MONTHS = ("january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
          "november", "december")
NUMBER_WORDS = ("one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve",
                "fifteen", "twenty", "thirty", "few", "couple of")
DURATION = re.compile(r"\b(\d+|" + "|".join(NUMBER_WORDS) + r")\s+(minute|hour|day|week|month|year)s?\b")
# Code in a spoken reply: name(...) or a JSON object.
WRITTEN_CALL = re.compile(r"\b[a-z_]+\s*\(\s*[\"'{]|\{\s*\"\w+\"\s*:", re.I)
CURRENCY = re.compile(r"\$|\busd\b|\bdollars?\b|\brs\.?\s*\d|₹|\blakhs?\b|\bcrores?\b")


def ungrounded(said: str, heard: str) -> list[str]:
    """Facts in an agent reply that nothing earlier in the call gave it: the script, the caller or a tool.

    A small model's worst habit on a phone call is inventing an amount, a date or a deadline; every number,
    currency, weekday, month and length of time the agent says must already have been heard in the call.
    ``said`` and ``heard`` are lower case.
    """
    out = []
    numbers_heard = set(re.findall(r"\d+", heard))
    for n in re.findall(r"\d+", said):
        if n not in numbers_heard:
            out.append(f"says a number nothing in the call gave ({n})")
    if (m := CURRENCY.search(said)) and not CURRENCY.search(heard):
        out.append(f"names a currency nothing in the call used ({m.group(0)})")
    for word in WEEKDAYS + MONTHS:
        if re.search(rf"\b{word}\b", said) and not re.search(rf"\b{word}\b", heard):
            # "may" is also an ordinary word ("may I have your name"): only a date counts.
            if word == "may" and not re.search(r"\b(\d+\s+may|may\s+\d+)\b", said):
                continue
            out.append(f"names a day or month nothing in the call gave ({word})")
    for m in DURATION.finditer(said):
        if m.group(0) not in heard:
            out.append(f"states a length of time nothing in the call gave ({m.group(0)})")
    return out


def settles_the_call(name: str, res: dict) -> str | None:
    """LlmBrain.settlesTheCall(): the outcome a booked call-back or the identity lock-out already recorded, or None.

    The live CRM then ends the call on the model's goodbye without a review hand-over, even if the model never
    called log_call_outcome."""
    if not res.get("ok"):
        return None
    if name == "schedule_callback":
        return "CALLBACK_REQUESTED"
    d = res.get("data") or {}
    if name == "verify_identity" and d.get("verified") is False and isinstance(d.get("attempts_left"), int) and d["attempts_left"] <= 0:
        return "VERIFICATION_FAILED"
    return None


def check(rec: dict, crm_net: bool = False) -> list[str]:
    """Every reason this conversation must not be trained on; empty when it is a good example.

    crm_net=True judges the call as the live CRM handles it: a goodbye after an outcome that a call-back or the
    identity lock-out already recorded (settles_the_call) counts as recorded. Training data keeps the strict default,
    so the model still learns to call log_call_outcome itself."""
    problems: list[str] = []
    msgs = rec["messages"]
    meta = rec.get("meta", {})
    if msgs[0]["role"] != "system" or msgs[1] != {"role": "user", "content": CONNECTED} or msgs[2]["role"] != "assistant":
        return ["does not start like a real call"]
    private = [v for v in meta.get("private_values", []) if v]
    never = [v for v in meta.get("never_values", []) if v and len(v) >= 4]
    tool_text = ""
    faq_text = ""          # general answers may contain the same words as a private detail ("6 pm")
    heard = msgs[0]["content"].lower()   # what the agent may state: the script, the caller's words, tool results
    verified = False
    closing_at = None
    settled = None           # outcome recorded by the CRM itself (only counted with crm_net)
    pending: set[str] = set()
    for i, m in enumerate(msgs[3:], start=3):
        role = m["role"]
        if role == "tool":
            if m.get("tool_call_id") not in pending:
                problems.append(f"message {i}: tool result without a call")
            pending.discard(m.get("tool_call_id"))
            tool_text += m["content"]
            heard += " " + m["content"].lower()
            if m.get("name") == "search_faq":
                faq_text += m["content"].lower()
            res = json.loads(m["content"])
            if m.get("name") == "verify_identity" and res.get("data", {}).get("verified"):
                verified = True
            if res.get("ok") and m.get("name") in CLOSING_TOOLS and closing_at is None:
                closing_at = i
            if crm_net and settled is None:
                settled = settles_the_call(m.get("name"), res)
            continue
        if pending:
            problems.append(f"message {i}: tool calls left unanswered")
            pending.clear()
        if role == "user":
            if closing_at is not None:
                problems.append("the caller spoke after the call was being closed")
            if msgs[i - 1]["role"] == "user":
                # On a real call every caller turn gets the agent's answer before the caller speaks again.
                problems.append(f"message {i}: the caller speaks twice in a row")
            heard += " " + (m.get("content") or "").lower()
            continue
        text = m.get("content") or ""
        if closing_at is not None and (m.get("tool_calls") or i != len(msgs) - 1):
            problems.append("more than one goodbye after the closing tool")
        for c in m.get("tool_calls") or []:
            fn = c["function"]
            problems += [f"message {i}: {p}" for p in check_call(fn["name"], fn["arguments"])]
            pending.add(c["id"])
        if not text and not m.get("tool_calls"):
            problems.append(f"message {i}: empty reply")
        if text:
            if text != spoken(text):
                problems.append(f"message {i}: markdown or symbols in a spoken reply")
            if EMOJI.search(text):
                problems.append(f"message {i}: emoji")
            if sentences(text) > 3 or len(text.split()) > 60:
                problems.append(f"message {i}: too long to speak ({len(text.split())} words)")
            low = text.lower()
            for v in never:
                if v.lower() in low:
                    problems.append(f"message {i}: repeats a detail that is never shared ({v})")
            if not verified:
                for v in private:
                    if v.lower() in low and v.lower() not in faq_text:
                        problems.append(f"message {i}: shares a private detail before verification ({v})")
            for amount in re.findall(r"\d[\d,]*(?:\.\d+)? rupees", text):
                if not re.search(r"(?<![\d,.])" + re.escape(amount), tool_text):
                    problems.append(f"message {i}: says an amount no tool returned ({amount})")
            problems += [f"message {i}: {p}" for p in ungrounded(low, heard)]
            if WRITTEN_CALL.search(text) or any(n.replace("_", "") in low.replace("_", "") for n in TOOL_NAMES):
                problems.append(f"message {i}: speaks a tool call aloud")
            if closing_at is None and settled is None and re.search(r"\b(good\s?bye|bye)\b", low):
                problems.append(f"message {i}: says goodbye before the call is recorded")
            if re.search(r"\b(i am|i'm) (a )?(human|real person)\b", low):
                problems.append(f"message {i}: claims to be human")
    last = msgs[-1]
    if last["role"] != "assistant" or last.get("tool_calls") or not last.get("content"):
        problems.append("does not end with the agent's goodbye")
    if closing_at is None and settled is None:
        problems.append("ends without log_call_outcome, opt_out or request_human_handoff")
    problems += check_expectation(rec, settled)
    return problems


def check_call(name: str, args) -> list[str]:
    tool = next((t["function"] for t in TOOLS if t["function"]["name"] == name), None)
    if tool is None:
        return [f"unknown tool {name}"]
    if name == "get_call_context":
        return ["calls get_call_context although the context is already in the prompt"]
    if not isinstance(args, dict):
        return [f"{name}: arguments are not an object"]
    params = tool["parameters"]
    out = [f"{name}: missing {r}" for r in params.get("required", []) if not args.get(r)]
    for k, v in args.items():
        spec = params["properties"].get(k)
        if spec is None:
            out.append(f"{name}: unknown argument {k}")
        elif "enum" in spec and v not in spec["enum"]:
            out.append(f"{name}: {k}={v!r} is not one of {spec['enum']}")
        elif spec.get("type") == "string" and not isinstance(v, str):
            out.append(f"{name}: {k} should be text")
        elif spec.get("type") == "object" and not isinstance(v, dict):
            out.append(f"{name}: {k} should be an object")
    return out


def calls_in(rec: dict) -> list[tuple[str, dict]]:
    return [(c["function"]["name"], c["function"]["arguments"]) for m in rec["messages"] if m["role"] == "assistant"
            for c in m.get("tool_calls") or []]


def check_expectation(rec: dict, settled: str | None = None) -> list[str]:
    """What the scenario says must happen, such as WILL_ACT being logged or a person taking over.

    ``settled``: an outcome the CRM recorded itself (see settles_the_call); it counts as that log_call_outcome."""
    expect = rec.get("meta", {}).get("expect") or {}
    calls = calls_in(rec)
    if settled:
        calls = calls + [("log_call_outcome", {"outcome_code": settled})]
    out = []
    for name, want in expect.get("calls", {}).items():
        hits = [a for n, a in calls if n == name]
        if not hits:
            out.append(f"expected a call to {name}")
        elif want and not any(all(a.get(k) == v for k, v in want.items()) for a in hits):
            out.append(f"expected {name} with {want}")
    for name in expect.get("no_calls", []):
        if any(n == name for n, _ in calls):
            out.append(f"should not call {name}")
    for phrase in expect.get("says", []):
        if not any(phrase.lower() in (m.get("content") or "").lower() for m in rec["messages"][3:] if m["role"] == "assistant"):
            out.append(f"expected the agent to say '{phrase}'")
    return out


def record(call: FakeCrm, messages: list[dict], source: str, scenario: str, expect: dict) -> dict:
    p = call.person
    private, never = [], []
    if p:
        for f in call.script.fields:
            v = p.values.get(f.key)
            if not v:
                continue
            forms = {v, p.said[f.key]} | ({spoken_date(dt.date.fromisoformat(v))} if f.type == "DATE" else set())
            forms = {x for x in forms if len(x) >= 4 and not x.isdigit() or (x.isdigit() and len(x) >= 5)}
            (private if f.access == "AFTER_VERIFY" else never if f.access == "NONE" else []).extend(forms)
    return {"messages": messages, "tools": TOOLS,
            "meta": {"source": source, "script": call.script.name, "industry": call.script.industry, "scenario": scenario,
                     "expect": expect, "private_values": sorted(private), "never_values": sorted(never),
                     **({"crm_corrections": call.corrections} if call.corrections else {}),
                     **({"thinking_turns": call.thinking} if call.thinking else {})}}


# --------------------------------------------------------------------------------------------- local model

TOOLS_IN_TEXT = ("# Tools\n\nYou may call one or more functions to assist with the user query.\n\n"
                 "You are provided with function signatures within <tools></tools> XML tags:\n<tools>{}\n</tools>\n\n"
                 "For each function call, return a json object with function name and arguments within "
                 "<tool_call></tool_call> XML tags:\n<tool_call>\n{{\"name\": <function-name>, \"arguments\": <args-json-object>}}\n</tool_call>")
TOOL_CALL_TAG = re.compile(r"(?s)<tool_call>\s*(\{.*?\})\s*</tool_call>")


def tools_in_system(messages: list[dict], tools: list[dict]) -> list[dict]:
    """The tool list written into the system message exactly as the Qwen3 chat template (and training) writes it.

    Ollama 0.40's own tool support mangles nested arguments: verify_identity's {"full_name": ..., "answers": {...}}
    comes back as {"full_name": "Name, answers: {"} although the model wrote it correctly. So the tools are not
    passed to Ollama at all and the model's <tool_call> text is read here instead."""
    block = TOOLS_IN_TEXT.format("".join("\n" + json.dumps(t, ensure_ascii=False) for t in tools))
    if messages and messages[0]["role"] == "system":
        return [dict(messages[0], content=messages[0]["content"] + "\n\n" + block)] + messages[1:]
    return [{"role": "system", "content": block}] + messages


def read_tool_call_tags(text: str, names: list[str]) -> tuple[str, list[dict]]:
    """Every <tool_call>{"name": ..., "arguments": {...}}</tool_call> in the reply, and the text around them."""
    calls = []
    for m in TOOL_CALL_TAG.finditer(text):
        try:
            obj = json.loads(m.group(1))
        except json.JSONDecodeError:
            obj = None
        if isinstance(obj, dict) and obj.get("name") in names:
            args = obj.get("arguments")
            if isinstance(args, str):
                try:
                    args = json.loads(args)
                except json.JSONDecodeError:
                    args = {"_unparsed": args}
            calls.append({"id": f"call_t{len(calls)}", "type": "function",
                          "function": {"name": obj["name"], "arguments": args if isinstance(args, dict) else {}}})
    rest = TOOL_CALL_TAG.sub(" ", text) if calls else text
    return re.sub(r"\s{2,}", " ", rest).strip(), calls


def ollama_chat(model: str, messages: list[dict], tools: list[dict] | None = None, temperature: float = 0.7,
                url: str = "http://localhost:11434", max_tokens: int = 400, timeout: int = 300,
                think: bool | None = None) -> dict:
    """One OpenAI-compatible chat request to Ollama. Tool arguments go out as JSON text and come back as dicts.

    The tools travel inside the system message and the calls are read from the reply text (see tools_in_system).
    think=False switches off the hidden reasoning of hybrid models such as qwen3:8b ("reasoning_effort": "none");
    leave it None for models that never think (qwen3:4b-instruct): Ollama may refuse the field there."""
    if tools:
        messages = tools_in_system(messages, tools)
    wire = []
    for m in messages:
        x = {k: v for k, v in m.items() if k != "name" or m["role"] == "tool"}
        if m.get("tool_calls"):
            x["tool_calls"] = [{"id": c["id"], "type": "function", "function": {"name": c["function"]["name"],
                               "arguments": json.dumps(c["function"]["arguments"], ensure_ascii=False)}} for c in m["tool_calls"]]
        wire.append(x)
    body = {"model": model, "messages": wire, "temperature": temperature, "max_tokens": max_tokens, "stream": False,
            "keep_alive": "30m"}
    if think is False:
        body["reasoning_effort"] = "none"
    req = urllib.request.Request(url + "/v1/chat/completions", json.dumps(body).encode(), {"Content-Type": "application/json"})
    with urllib.request.urlopen(req, timeout=timeout) as r:
        res = json.load(r)
    msg = res["choices"][0]["message"]
    calls = []
    for i, c in enumerate(msg.get("tool_calls") or []):
        raw = c["function"].get("arguments") or "{}"
        try:
            args = json.loads(raw) if isinstance(raw, str) else raw
        except json.JSONDecodeError:
            args = {"_unparsed": raw}
        calls.append({"id": c.get("id") or f"call_{i}", "type": "function", "function": {"name": c["function"]["name"], "arguments": args}})
    content = msg.get("content") or ""
    if not calls and tools:
        names = [t["function"]["name"] for t in tools]
        content, calls = read_tool_call_tags(content, names)
        if not calls:
            content, calls = recover_written_calls(content, names)
    return {"content": content, "tool_calls": calls}


WRITTEN_FUNCTION = re.compile(r"(?s)\b([A-Za-z_]+)\s*\(\s*(\{.*?\})\s*\)")
WRITTEN_JSON = re.compile(r"(?s)(?:<tool_call>\s*)?(\{\s*\"name\"\s*:.*\})\s*(?:</tool_call>)?")


def recover_written_calls(text: str, names: list[str]) -> tuple[str, list[dict]]:
    """LlmClients.recoverWrittenCalls(): a tool call the model wrote as text is made, never spoken."""
    if "{" not in text:
        return text, []
    known = {n: n for n in names} | {n.replace("_", "").lower(): n for n in names}
    calls, spans = [], []
    for m in WRITTEN_FUNCTION.finditer(text):
        tool = known.get(m.group(1)) or known.get(m.group(1).replace("_", "").lower())
        try:
            args = json.loads(m.group(2))
        except json.JSONDecodeError:
            continue
        if tool and isinstance(args, dict):
            calls.append({"id": f"call_w{len(calls)}", "type": "function", "function": {"name": tool, "arguments": args}})
            spans.append(m.span())
    if not calls:
        m = WRITTEN_JSON.search(text)
        try:
            obj = json.loads(m.group(1)) if m else None
        except json.JSONDecodeError:
            obj = None
        if isinstance(obj, dict) and known.get(str(obj.get("name"))) and isinstance(obj.get("arguments", {}), dict):
            calls.append({"id": "call_w0", "type": "function",
                          "function": {"name": known[str(obj["name"])], "arguments": obj.get("arguments", {})}})
            spans.append(m.span())
    for start, end in reversed(spans):
        text = text[:start] + text[end:]
    return re.sub(r"\s{2,}", " ", text).strip(), calls


def installed_models(url: str = "http://localhost:11434") -> list[str]:
    with urllib.request.urlopen(url + "/api/tags", timeout=10) as r:
        return [m["name"] for m in json.load(r)["models"]]


def write_jsonl(path: Path, rows, mode: str = "w") -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open(mode, encoding="utf-8") as f:
        for r in rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")


def read_jsonl(path: Path) -> list[dict]:
    if not path.exists():
        return []
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]
