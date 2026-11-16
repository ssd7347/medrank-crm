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

import collections
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


# --------------------------------------------------------------------------------------------- call-back times

# CallbackTime and HandoffService's desk-time maths (the CRM), ported: the time a caller is promised for the time they
# asked for. Kept honest by TrainingSpecTest's callback_time_examples (the CRM's own answers for ~80 requests at five
# moments), which test_callkit checks this port against. All times here are naive datetimes in Indian time.
DESK_OPEN, DESK_CLOSE = (dt.time.fromisoformat(t) for t in SPEC["callback_desk_hours"])
MIN_NOTICE = dt.timedelta(minutes=15)
USUAL_WAIT = dt.timedelta(hours=2)


def _clock_text(t) -> str:
    hour = t.hour % 12 or 12
    return f"{hour}:{t.minute:02d} {'AM' if t.hour < 12 else 'PM'}"


def _day_text(d) -> str:
    return f"{d:%A} {d.day} {d:%B}"


@dataclass(frozen=True)
class CallbackPromise:
    """CallbackTime.Promise: when follows "will call you back"; slot is the tool's confirmed_slot; note (the desk
    hours) comes first; start is the start of what the caller asked for, None for the usual time."""
    due: dt.datetime
    when: str
    slot: str
    note: str | None = None
    start: dt.datetime | None = None

    def message(self) -> str:
        promise = "a member of our team will call you back " + self.when + "."
        return "A" + promise[1:] if self.note is None else self.note + promise

    def asked(self) -> bool:
        return self.start is not None

    def allows(self, t: dt.datetime) -> bool:
        return self.start is not None and self.start <= t <= self.due

    def with_note(self, note: str | None) -> "CallbackPromise":
        return CallbackPromise(self.due, self.when, self.slot, note, self.start)


def _by(due: dt.datetime, start: dt.datetime | None = None) -> CallbackPromise:
    return CallbackPromise(due, "by " + spoken_time(due), spoken_time(due), None, start)


def _at(at: dt.datetime) -> CallbackPromise:
    return CallbackPromise(at, f"on {_day_text(at)} at {_clock_text(at)}", spoken_time(at), None, at)


def desk_time(now: dt.datetime, open_: dt.time, close: dt.time) -> dt.datetime:
    """HandoffService.deskTime: now if the desk is open (Monday to Saturday, desk hours); otherwise its next opening."""
    if now.weekday() != 6 and open_ <= now.time() < close:
        return now
    nxt = dt.datetime.combine(now.date() if now.time() < open_ else now.date() + dt.timedelta(days=1), open_)
    return nxt + dt.timedelta(days=1) if nxt.weekday() == 6 else nxt


def plus_desk_time(now: dt.datetime, allowed: dt.timedelta, open_: dt.time = DESK_OPEN, close: dt.time = DESK_CLOSE) -> dt.datetime:
    """HandoffService.plusDeskTime: allowed counted in desk time only."""
    t = desk_time(now, open_, close)
    if not open_ < close:
        return t + allowed
    left = allowed
    for _ in range(14):
        closing = dt.datetime.combine(t.date(), close)
        today = closing - t
        if left <= today:
            return t + left
        left -= today
        t = desk_time(closing, open_, close)
    return t + left


@dataclass(frozen=True)
class _Asked:
    day: dt.date | None
    start: dt.time | None
    end: dt.time | None
    exact: bool
    by_only: bool
    within: dt.timedelta | None


_WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday"
_TOMORROW = "tomorrow|tomorow|tommorow|tommorrow|tmrw|tmr"
_A = re.ASCII
_NEGATED = re.compile(r"(?:\b(?:not|never|except|dont|cant|cannot|wont|busy)|n't)\s+(?:\S+\s+){0,3}?"
                      r"(?:today|" + _TOMORROW + r"|tonight|morning|afternoon|evening|night|" + _WEEKDAYS + r"|\d)", _A)
_GREETING = re.compile(r"\bgood\s+(?:morning|afternoon|evening|night)\b", _A)
_DAY_AFTER = re.compile(r"\bday\s+after\s+(?:" + _TOMORROW + r")\b", _A)
_DAY_WORD = re.compile(r"\b(?:(" + _TOMORROW + r")|(today|tonight)|(next\s+)?(" + _WEEKDAYS + r")s?)\b", _A)
_THIS_PART = re.compile(r"\bthis\s+(?:morning|afternoon|evening)\b", _A)
_PART = re.compile(r"\b(morning|afternoon|evening|night|tonight)\b", _A)
_RELATIVE = re.compile(r"\b(in|after|within)\s+(?:(?:(an?|one|\d{1,2}(?:\.5)?)\s*(?:hours?|hrs?)(\s+and\s+a\s+half)?)|"
                       r"(half\s+an?\s+hour)|(\d{1,3})\s*(?:minutes?|mins?))\b", _A)
_T = r"(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|oclock)?"
_BETWEEN = re.compile(r"\b(?:between|from)\s+" + _T + r"\s*(?:and|to|till|until|-)\s*" + _T + r"\b", _A)
_RANGE = re.compile(r"\b" + _T + r"\s*(?:-|to)\s*(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm)\b", _A)
_TIME_SAID = re.compile(r"(?:\b(at|after|before|by|around|about|till|until|untill)\s+)?"
                        r"\b(\d{1,2})(?:([:.])(\d{2}))?(?:\s*(am|pm|oclock))?\b(?!\s*(?:hours?|hrs?|minutes?|mins?|days?|%))", _A)
_WEEKDAY_INDEX = {d: i for i, d in enumerate(_WEEKDAYS.split("|"))}


def _clock(h: str, m: str | None, mer: str | None) -> dt.time | None:
    """CallbackTime.clock: "4 pm" is 16:00; a bare 1 to 7 is the afternoon, 8 to 11 the morning, 12 noon."""
    hour, minute = int(h), 0 if m is None else int(m)
    if minute > 59:
        return None
    if mer in ("am", "pm"):
        if hour < 1 or hour > 12:
            return None
        hour = hour % 12 + (12 if mer == "pm" else 0)
    elif hour > 23:
        return None
    elif 1 <= hour <= 7:
        hour += 12
    return dt.time(hour, minute)


def _read_asked(asked: str | None, today: dt.date) -> _Asked | None:
    """CallbackTime.read: None when the request is missing, unclear, or says two different things."""
    if asked is None or not asked.strip():
        return None
    s = re.sub(r"[\u2018\u2019`]", "'", asked.lower())
    s = re.sub(r"\ba\.?\s?m\b\.?", "am", s, flags=_A)
    s = re.sub(r"\bp\.?\s?m\b\.?", "pm", s, flags=_A)
    s = re.sub(r"\bo'?\s?clock\b|\bbaje\b", "oclock", s, flags=_A)
    s = " " + re.sub(r"\b(?:12\s*)?noon\b|\bmidday\b", "12:00 pm", s, flags=_A) + " "
    if _NEGATED.search(s):
        return None
    s = _GREETING.sub(" ", s)

    days: dict[dt.date, None] = {}
    for _ in _DAY_AFTER.finditer(s):
        days[today + dt.timedelta(days=2)] = None
    s = _DAY_AFTER.sub(" ", s)
    if _THIS_PART.search(s):
        days[today] = None
    for m in _DAY_WORD.finditer(s):
        if m.group(1) is not None:
            days[today + dt.timedelta(days=1)] = None
        elif m.group(2) is not None:
            days[today] = None
        else:
            ahead = (_WEEKDAY_INDEX[m.group(4)] - today.weekday()) % 7
            if ahead == 0 and m.group(3) is not None:
                ahead = 7                                   # "next Monday" on a Monday: a week on
            days[today + dt.timedelta(days=ahead)] = None
    if len(days) > 1:
        return None
    day = next(iter(days), None)

    within, by_only, relatives = None, False, 0
    for m in _RELATIVE.finditer(s):
        relatives += 1
        by_only = m.group(1) == "within"
        if m.group(4) is not None:
            within = dt.timedelta(minutes=30)
        elif m.group(5) is not None:
            within = dt.timedelta(minutes=int(m.group(5)))
        else:
            n = m.group(2)
            hours = 1.0 if n.startswith("a") or n == "one" else float(n)
            within = dt.timedelta(minutes=int(hours * 60 + 0.5) + (30 if m.group(3) is not None else 0))
    s = _RELATIVE.sub(" ", s)

    windows: list[tuple[dt.time, dt.time]] = []
    for rng_ in (_BETWEEN, _RANGE):
        for m in rng_.finditer(s):
            b = _clock(m.group(4), m.group(5), m.group(6))
            a = _clock(m.group(1), m.group(2), m.group(3) if m.group(3) is not None else m.group(6))
            if a is not None and b is not None and m.group(3) is None and a > b and a.hour >= 12:
                a = a.replace(hour=a.hour - 12)                # "between 11 and 1 pm": 11 in the morning
                if a.hour < 8:
                    return None                                # "between 5 and 3 pm": not 5 in the morning
            if a is None or b is None or not a < b:
                return None
            windows.append((a, b))
        s = rng_.sub(" ", s)

    times: list[_Asked] = []
    for m in _TIME_SAID.finditer(s):
        cue, sep, mer = m.group(1), m.group(3), m.group(5)
        if cue is None and mer is None and sep != ":":
            continue                                           # a bare number is not a time
        t = _clock(m.group(2), m.group(4), mer)
        if t is None:
            return None
        cue = cue or "at"
        if cue in ("before", "by", "till", "until", "untill"):
            times.append(_Asked(day, None, t, False, True, None))
        elif cue == "after":
            times.append(_Asked(day, t, dt.time.max if t.hour == 23 else t.replace(hour=t.hour + 1), False, False, None))
        else:
            times.append(_Asked(day, t, t, True, False, None))

    parts = list(dict.fromkeys("night" if m.group(1) == "tonight" else m.group(1) for m in _PART.finditer(s)))

    if relatives > 0:
        return (_Asked(None, None, None, False, by_only, within)
                if relatives == 1 and day is None and not windows and not times and not parts else None)
    if len(windows) + len(times) > 1:
        return None
    if windows:
        return _Asked(day, windows[0][0], windows[0][1], False, False, None)
    if times:
        return times[0]
    if len(parts) == 1:
        lo, hi = {"morning": (6, 12), "afternoon": (12, 16), "evening": (16, 19)}.get(parts[0], (19, 22))
        return _Asked(day, dt.time(lo), dt.time(hi), False, False, None)
    if day is not None and day > today:
        return _Asked(day, dt.time.min, dt.time.max, False, False, None)    # "tomorrow": any time that day
    return None


def _round_up(at: dt.datetime) -> dt.datetime:
    z = at.replace(second=0, microsecond=0)
    over = z.minute % 5
    return z if over == 0 and z == at else z + dt.timedelta(minutes=5 - over)


def _in_hours(at: dt.datetime, open_: dt.time, close: dt.time) -> bool:
    return at.weekday() != 6 and open_ <= at.time() < close


def _next_working_day(day: dt.date) -> dt.date:
    nxt = day + dt.timedelta(days=1)
    return nxt + dt.timedelta(days=1) if nxt.weekday() == 6 else nxt


def callback_promise(asked: str | None, now: dt.datetime, open_: dt.time = DESK_OPEN, close: dt.time = DESK_CLOSE,
                     fallback: dt.datetime | None = None) -> CallbackPromise:
    """CallbackTime.promise: the time asked for when it is clear and can be kept, otherwise the usual "by" time
    (fallback; by default two hours of desk time), told the desk hours first when the request was outside them."""
    if fallback is None:
        fallback = plus_desk_time(now, USUAL_WAIT, open_, close)
    a = _read_asked(asked, now.date())
    if a is None:
        return _by(fallback)
    outside = CallbackPromise(fallback, "by " + spoken_time(fallback), spoken_time(fallback),
                              f"Our team makes calls between {_clock_text(open_)} and {_clock_text(close)}, Monday to Saturday, so ")
    if a.within is not None:
        at = _round_up(now + a.within)
        if a.within < MIN_NOTICE:
            return _by(fallback)
        if not _in_hours(at, open_, close):
            return outside
        return _by(at, now) if a.by_only else _at(at)
    day = a.day if a.day is not None else now.date()
    for tries in range(2):
        if day.weekday() == 6:
            return outside
        if a.exact:
            if a.start < open_ or not a.start < close:
                return outside
            at = dt.datetime.combine(day, a.start)
            if at < now and a.day is None and tries == 0:
                day = _next_working_day(day)
                continue
            return _by(fallback) if at < now + MIN_NOTICE else _at(at)
        lo = open_ if a.start is None or a.start < open_ else a.start
        hi = close if a.end > close else a.end
        if dt.datetime.combine(day, hi) - dt.datetime.combine(day, lo) < MIN_NOTICE:
            return outside
        start, end = dt.datetime.combine(day, lo), dt.datetime.combine(day, hi)
        if end < now + MIN_NOTICE:
            if a.day is None and tries == 0:
                day = _next_working_day(day)
                continue
            return _by(fallback)
        if a.by_only or not start > now:
            return _by(end, now)
        slot = f"{_day_text(day)}, between {_clock_text(lo)} and {_clock_text(hi)}"
        return CallbackPromise(end, "on " + slot, slot, None, start)
    return _by(fallback)


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
    handed_over: str | None = None  # the reason of this call's hand-over so far (VoiceCall.handoffReason)
    closing: bool = False
    settled: bool = False          # a call-back or the identity lock-out already recorded the outcome (settles_the_call)
    over: bool = False
    tool_log: list[tuple[str, dict, dict]] = field(default_factory=list)   # (name, args, parsed result)
    corrections: list[list[str]] = field(default_factory=list)   # facts the CRM's FactGuard stopped (see sim.py)
    reason_stops: list[list[str]] = field(default_factory=list)  # replies stopped for naming the reason too soon
    name_asks: int = 0                                           # identity checks with only part of the name
    thinking: int = 0              # replies with hidden reasoning (removed before speaking, but they cost time)
    upgrades: list[dict] = field(default_factory=list)   # call-backs / hand-overs the CRM made urgent (upgrade())
    callback: tuple[int, CallbackPromise] | None = None  # this call's booked call-back: (id, what was promised)
    clock: dt.datetime | None = None                     # "now" for this call (chosen on first use)

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

    def _now(self) -> dt.datetime:
        """A weekday morning in October 2026, the same all through the call."""
        if self.clock is None:
            self.clock = dt.datetime(2026, 10, 12, 11, 0) + dt.timedelta(days=self.rng.randint(0, 2))
        return self.clock

    def _callback_slot(self, minutes: int) -> str:
        return spoken_time(self._now() + dt.timedelta(minutes=minutes))

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
        if len(_words(str(args.get("full_name") or ""))) < len(_words(self.person.full_name)):
            # As VoiceTools.verifyIdentity: a first name alone is not a try; the caller is asked for the full name, up
            # to twice. After that asking again only loops, so a person takes over.
            self.name_asks += 1
            if self.name_asks <= SPEC["max_name_asks"]:
                return result_json(False, code="INVALID_ARGUMENT", msg="Could I have your full name, please, your first name and surname?")
            self.failures = 3
            mode, msg = self._hand_over("VERIFICATION_FAILED", transfer=False)
            return result_json(True, {"verified": False, "attempts_left": 0, "handoff_mode": mode}, "I could not verify the details. " + msg)
        name_ok = _words(self.person.full_name) <= _words(str(args.get("full_name") or ""))
        answers = {str(k).lower(): str(v) for k, v in args.items() if isinstance(v, str)}
        if isinstance(args.get("answers"), dict):
            answers.update({str(k).lower(): str(v) for k, v in args["answers"].items() if v is not None})
        checks = self.verify_fields()
        if name_ok and checks and not any(str(answers.get(f.key, answers.get(f.label.lower())) or "").strip() for f in checks):
            # As VoiceTools.verifyIdentity: the right name with no answer yet is asked for, not counted as a wrong answer.
            self.name_asks += 1
            if self.name_asks <= SPEC["max_name_asks"]:
                return result_json(False, code="INVALID_ARGUMENT", msg=f"Thank you. Could I also have your {checks[0].label}, please?")
            self.failures = 3
            mode, msg = self._hand_over("VERIFICATION_FAILED", transfer=False)
            return result_json(True, {"verified": False, "attempts_left": 0, "handoff_mode": mode}, "I could not verify the details. " + msg)
        extra_ok = not checks or any(answer_matches(f, self.person.values.get(f.key), answers.get(f.key, answers.get(f.label.lower()))) for f in checks)
        if name_ok and extra_ok:
            self.verified = True
            return result_json(True, {"verified": True, "attempts_left": 3 - self.failures}, "Thank you, that's verified.")
        self.failures += 1
        left = 3 - self.failures
        if left <= 0:
            mode, msg = self._hand_over("VERIFICATION_FAILED", transfer=False)
            return result_json(True, {"verified": False, "attempts_left": 0, "handoff_mode": mode}, "I could not verify the details. " + msg)
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
        # As HandoffService.callback: the caller is promised the time they asked for when it can be kept
        # (callback_promise). Booking twice in one call makes one task; a new clear time the booked one does not fit
        # moves it, anything else keeps the booked time (after the desk hours, if they asked outside them).
        asked = str(args.get("preferred_time_text") or "").strip() or None
        promise = callback_promise(asked, self._now())
        if self.callback is None:
            self.callback = (self.rng.randint(100, 999), promise)
        else:
            cb_id, booked = self.callback
            if promise.asked() and not promise.allows(booked.due):
                self.callback = (cb_id, promise)
            else:
                promise = booked if promise.note is None else booked.with_note(promise.note)
        return result_json(True, {"callback_id": self.callback[0], "confirmed_slot": promise.slot}, promise.message())

    def t_request_human_handoff(self, args):
        reason = handoff_reason(args.get("reason_code"))
        if reason is None:
            # VoiceTools.handoff: an invented reason is refused and the model asks again; nothing is handed over.
            return result_json(False, code="INVALID_ARGUMENT", msg="reason_code must be one of: " + ", ".join(SPEC["handoff_reasons"])
                               + ". A wrong person is not a hand-over: use log_call_outcome with WRONG_PERSON.")
        mode, msg = self._hand_over(reason)
        return result_json(True, {"handoff_mode": mode, "message": msg}, msg)

    def _hand_over(self, reason: str, transfer: bool = True) -> tuple[str, str]:
        """HandoffService.handoff(): records the outcome; a second hand-over in the call that is not more urgent
        than the first makes no new task. transfer=False: the identity lock-out, always a call-back in this kit."""
        if reason == "VERIFICATION_FAILED":
            self.outcome = "VERIFICATION_FAILED"
        elif self.outcome not in ("OPTED_OUT", "VERIFICATION_FAILED"):
            self.outcome = "HANDED_OFF"
        if self.handed_over is not None and urgency(reason) <= urgency(self.handed_over):
            return "ALREADY_ARRANGED", "Our team already has your request from this call, so they will be in touch as arranged."
        self.handed_over = reason
        if self.desk_open and transfer:
            return "TRANSFER_NOW", "I am connecting you to a member of our team now. Please stay on the line."
        minutes = 15 if reason == "DISTRESS" else 30 if reason == "COMPLAINT_OR_DISPUTE" else 120
        return "CALLBACK_TASK", "A member of our team will call you back by " + self._callback_slot(minutes) + "."

    def t_log_call_outcome(self, args):
        code = str(args.get("outcome_code") or "").upper()
        if code not in SPEC["outcomes"]:
            return result_json(False, code="INVALID_ARGUMENT", msg="Unknown outcome code.")
        # As VoiceTools.logOutcome: an opt-out, a lock-out or a hand-over is never overwritten, and a call-back that was
        # recorded, or that the caller asked for (CALLBACK_ASKED, added by upgrade()), but not booked gets booked; the
        # model's outcome is kept.
        settled = self.outcome in ("OPTED_OUT", "VERIFICATION_FAILED", "HANDED_OFF")
        data, say = {"recorded": True}, None
        asked = args.get(CALLBACK_ASKED)
        if (code == "CALLBACK_REQUESTED" or asked) and not settled and self.outcome != "CALLBACK_REQUESTED":
            booked = json.loads(self.t_schedule_callback({"preferred_time_text": "Caller said: " + asked} if asked else {}))
            data.update(booked["data"])
            say = booked["say"]
        if not settled:
            self.outcome = code
        return result_json(True, data, say)

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


# CallerSignals (the CRM), read from the spec so the kit can never drift from it: hand-over reason aliases, and the
# caller words that make a call-back or hand-over an urgent DISTRESS / COMPLAINT_OR_DISPUTE hand-over.
REASON_ALIASES: dict[str, str] = SPEC["handoff_reason_aliases"]
DISTRESS_WORDS = re.compile(SPEC["distress_words"])
DISPUTE_WORDS = re.compile(SPEC["dispute_words"])
OPT_OUT_WORDS = re.compile(SPEC["opt_out_words"])
CALLBACK_WORDS = re.compile(SPEC["callback_words"])
NO_CALLBACK_WORDS = re.compile(SPEC["no_callback_words"])
CALLBACK_WITHDRAWN_WORDS = re.compile(SPEC["callback_withdrawn_words"])
WITHDRAWN_CONDITION = re.compile(SPEC["callback_withdrawn_not_after"])
WITHDRAWN_LIMIT = re.compile(SPEC["callback_withdrawn_not_before"])
CALLBACK_ASKED: str = SPEC["callback_asked_arg"]
SENTENCE = re.compile(r"[^.?!]+[.?!]?")


def _asks(s: str) -> bool:
    return bool(CALLBACK_WORDS.search(s)) and not NO_CALLBACK_WORDS.search(s)


def callback_withdrawn(caller_words: str) -> str | None:
    """CallerSignals.callbackWithdrawn(): the caller's sentence taking a call-back back ("no need to call me back"),
    when that is the last thing they said about one; None otherwise (asked again, only a limit, or a condition)."""
    withdrawn = None
    for sentence in SENTENCE.findall((caller_words or "").lower()):
        s = sentence.strip()
        end = -1
        for w in CALLBACK_WITHDRAWN_WORDS.finditer(s):
            if not WITHDRAWN_CONDITION.search(s[:w.start()]):
                end = w.end()
        if end >= 0:
            rest = s[end:]
            limit = WITHDRAWN_LIMIT.search(re.split(r"[,;]", rest, maxsplit=1)[0])
            withdrawn = None if _asks(rest) or limit else s
        elif _asks(s):
            withdrawn = None
    return withdrawn


def callback_asked(caller_words: str) -> str | None:
    """CallerSignals.callbackAsked(): the caller's last sentence asking for a call-back or a new date, or None (also
    when they took it back later)."""
    if callback_withdrawn(caller_words) is not None:
        return None
    hits = [s.strip() for s in SENTENCE.findall((caller_words or "").lower()) if _asks(s)]
    return hits[-1] if hits else None


def handoff_reason(raw) -> str | None:
    """CallerSignals.reason(): the valid hand-over reason a model meant, or None."""
    if raw is None:
        return None
    r = re.sub(r"[\s-]+", "_", str(raw).strip().upper())
    r = REASON_ALIASES.get(r, r)
    return r if r in SPEC["handoff_reasons"] else None


def urgency(reason: str) -> int:
    """HandoffService.urgency(): distress, then complaints and disputes, then everything else."""
    return 2 if reason == "DISTRESS" else 1 if reason == "COMPLAINT_OR_DISPUTE" else 0


def heard_signal(caller_words: str) -> str | None:
    """CallerSignals.heard(): DISTRESS, COMPLAINT_OR_DISPUTE or None from everything the caller said."""
    s = (caller_words or "").lower()
    if DISTRESS_WORDS.search(s):
        return "DISTRESS"
    return "COMPLAINT_OR_DISPUTE" if DISPUTE_WORDS.search(s) else None


def upgrade(name: str, args: dict, caller_words: str) -> tuple[str, dict]:
    """CallerSignals.upgrade(): the tool the CRM actually runs. Returns (name, args) unchanged - the same args
    object - when nothing changes."""
    if name == "log_call_outcome":
        if OPT_OUT_WORDS.search((caller_words or "").lower()):
            return "opt_out", {"scope": "AI_CALLS"}
        asked = callback_asked(caller_words)
        if asked is None or CALLBACK_ASKED in args or str(args.get("outcome_code")).upper() == "CALLBACK_REQUESTED":
            return name, args     # nothing asked, or the outcome itself books it
        return name, {**args, CALLBACK_ASKED: asked}
    handoff = name == "request_human_handoff"
    if not handoff and name != "schedule_callback":
        return name, args
    signal = heard_signal(caller_words)
    if signal is None:
        return name, args
    asked = handoff_reason(args.get("reason_code")) if handoff else None
    if asked == signal or asked == "VERIFICATION_FAILED" or (asked == "DISTRESS" and signal == "COMPLAINT_OR_DISPUTE"):
        return name, args
    note = args.get("summary") if handoff else args.get("reason")
    return "request_human_handoff", {
        "reason_code": signal,
        "summary": ("The caller sounded distressed" if signal == "DISTRESS" else "The caller disputes a charge")
                   + " (marked urgent by the CRM)." + ("" if note is None else f" Agent's note: {note}")}


REASON_WORDS = re.compile(SPEC["reason_words"])
REASON_FALLBACK: str = SPEC["reason_fallback"]
AI_ASKED = re.compile(SPEC["ai_question_words"])


def reason_fallback(said: str) -> str:
    """FactGuard.reasonFallback(): the fixed line for what the caller just said."""
    return SPEC["reason_fallback_ai"] if AI_ASKED.search((said or "").lower()) else REASON_FALLBACK


def reason_words(said: str, allowed: str) -> list[str]:
    """FactGuard.reasonWords(): the words in a reply that give away why we are calling and ``allowed`` never used."""
    ok = set(REASON_WORDS.findall(allowed.lower()))
    return list(dict.fromkeys(w for w in REASON_WORDS.findall(said.lower()) if w not in ok))


def may_mention(system: str, opening: str) -> str:
    """LlmBrain.mayMention: the opening line and the identity questions (the call reason in the context does not count)."""
    return opening + " " + " ".join(re.findall(r'"ask_for":"([^"]*)"', system))


def reason_hidden(system: str) -> bool:
    """LlmBrain.reasonHidden at the start of a call: this call needs the identity check."""
    return '"verification_required":true' in system


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
    # What the agent may mention before the identity check: the script's purpose does not count here.
    mentioned = may_mention(msgs[0]["content"], msgs[2].get("content") or "")
    hidden = reason_hidden(msgs[0]["content"])
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
            mentioned += " " + m["content"]
            if m.get("name") == "search_faq":
                faq_text += m["content"].lower()
            res = json.loads(m["content"])
            if m.get("name") == "verify_identity" and res.get("data", {}).get("verified"):
                verified = True
            # With the CRM's safety net the tool that actually ran counts ("ran": a call-back the CRM made an urgent
            # hand-over); judged alone, the model's own call counts.
            ran = (m.get("ran") or m.get("name")) if crm_net else m.get("name")
            if res.get("ok") and ran in CLOSING_TOOLS and closing_at is None:
                closing_at = i
            if crm_net and settled is None:
                settled = settles_the_call(ran, res)
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
            mentioned += " " + (m.get("content") or "")
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
                if hidden and (early := reason_words(low, mentioned)):
                    problems.append(f"message {i}: names the reason for the call before verification ({', '.join(early)})")
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
    problems += check_expectation(rec, settled, crm_net)
    problems += identity_habits(rec, crm_net)
    return problems


# Scenarios whose caller is not the customer: there an identity lock-out is the right ending.
NOT_THE_CUSTOMER = {"verify_fail", "wrong_person"}
# A promise of a person ("I'll ask someone", "Sure, I can ask a person to help you") with no hand-over or call-back
# anywhere after it. An offer ("If you like, I can ask them to call you") is not a promise.
_WHO = r"(ask|get|arrange for|have) (a person|someone|a member|our team|a human)"
PROMISED_PERSON = re.compile(rf"\b(i|we)(?: will|'ll) {_WHO}|\b(sure|okay|ok|yes|of course),? (i|we) can {_WHO}", re.I)


def identity_habits(rec: dict, crm_net: bool = False) -> list[str]:
    """Faults found in the round 4 model-written calls (30 Oct 2026) that the rules above let through.

    Always: a genuine customer locked out after the agent checked a single name - registered names have two words,
    so the check could never pass (8B test call #49). Training data only (crm_net=False): the habits behind that and
    other bad lessons - any single-name identity check (ask for the full name first), the same reply three times in
    a row (a loop), and promising a person without the tool."""
    msgs = rec["messages"]
    out = []
    single = any(c["function"]["name"] == "verify_identity" and isinstance(c["function"]["arguments"], dict)
                 and len(str(c["function"]["arguments"].get("full_name") or "").split()) < 2
                 for m in msgs for c in m.get("tool_calls") or [])
    locked = any(m["role"] == "tool" and m.get("name") == "verify_identity" and '"attempts_left":0' in m["content"]
                 and '"verified":false' in m["content"] for m in msgs)
    if single and locked and rec.get("meta", {}).get("scenario") not in NOT_THE_CUSTOMER:
        out.append("locked out a genuine customer after checking a single name")
    if crm_net:
        return out
    if single:
        out.append("identity check with a single name (ask for the full name first)")
    replies = [" ".join(m["content"].lower().split()) for m in msgs[3:] if m["role"] == "assistant" and m.get("content")]
    if any(replies[i] == replies[i + 1] == replies[i + 2] for i in range(len(replies) - 2)):
        out.append("says the same reply three times in a row")
    for i, m in enumerate(msgs):
        if m["role"] == "assistant" and m.get("content") and not m.get("tool_calls") and PROMISED_PERSON.search(m["content"]):
            later = {c["function"]["name"] for x in msgs[i:] for c in x.get("tool_calls") or []}
            if not later & {"request_human_handoff", "schedule_callback"}:
                out.append("promises a person without calling the tool")
                break
    return out


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


def check_expectation(rec: dict, settled: str | None = None, crm_net: bool = False) -> list[str]:
    """What the scenario says must happen, such as WILL_ACT being logged or a person taking over.

    ``settled``: an outcome the CRM recorded itself (see settles_the_call); it counts as that log_call_outcome.
    With ``crm_net`` the urgent hand-overs the CRM made (meta "crm_upgrades", see upgrade()) count too."""
    expect = rec.get("meta", {}).get("expect") or {}
    calls = calls_in(rec)
    if settled:
        calls = calls + [("log_call_outcome", {"outcome_code": settled})]
    if crm_net:
        calls = calls + [(u["tool"], {}) if "tool" in u else ("request_human_handoff", {"reason_code": u["reason_code"]})
                         for u in rec.get("meta", {}).get("crm_upgrades", [])]
        # A call-back the CRM booked when the model only recorded that one was requested (VoiceTools.logOutcome).
        if any(m["role"] == "tool" and m.get("name") == "log_call_outcome" and '"callback_id"' in m["content"] for m in rec["messages"]):
            calls = calls + [("schedule_callback", {})]
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
                     **({"crm_reason_stops": call.reason_stops} if call.reason_stops else {}),
                     **({"thinking_turns": call.thinking} if call.thinking else {}),
                     **({"crm_upgrades": call.upgrades} if call.upgrades else {})}}


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
