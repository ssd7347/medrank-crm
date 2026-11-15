"""Fills the demo CRM (demo/start-demo-crm.cmd, port 8094) with a ready-to-show loans business.

    python demo/setup_demo.py            (the demo server must be running and empty)

What it adds: company branding, the "Loans and EMI collections" starter pack, its knowledge-base answers filled in
and switched on, the call scripts approved, 10 made-up customers (8 with consent to AI calls, 1 without, 1 marked
"Do not call"), and one campaign, not started, so it can be started in front of the client.

It refuses to run unless the server is on this computer, in simulated mode, with no contacts yet: it never touches a
real CRM. Standard library only.
"""
from __future__ import annotations

import datetime as dt
import json
import sys
import urllib.error
import urllib.request

BASE = "http://localhost:8094"
ADMIN_PHONE = "9000000000"
COMPANY = "Sunrise Finance"
AGENT = "Asha"

# label of a starter-pack FAQ -> the demo company's answer (the pack's answers have [placeholders]).
FAQ_ANSWERS = {
    "How can I pay my EMI?": "You can pay through the Sunrise Finance app, by UPI to sunrisefinance@upi, or at any of our "
                             "branches. Please keep your loan account number ready.",
    "Can I get a payment receipt?": "Receipts are sent to your registered email and mobile within one working day. A member "
                                    "of our team can resend it if needed.",
}

# Made-up customers. Phone numbers are fictional and this server never dials (simulated platform).
# due: days from today for the EMI due date (negative = overdue).
CUSTOMERS = [
    # name,             phone,        loan,          born,         emi,   due, consent, note
    ("Lakshmi Narayanan", "9876500201", "SF-LN-20417", "1984-03-12", 8500, 2, True, ""),
    ("Arjun Mehta", "9876500202", "SF-LN-20433", "1990-07-25", 12400, 4, True, ""),
    ("Fatima Sheikh", "9876500203", "SF-LN-20458", "1979-11-02", 6200, 6, True, "Prefers calls after 5 pm"),
    ("Suresh Babu", "9876500204", "SF-LN-20471", "1987-01-30", 9900, 3, True, ""),
    ("Meena Pillai", "9876500205", "SF-LN-20496", "1992-05-18", 4750, 12, True, ""),
    ("Rohan Das", "9876500206", "SF-LN-20502", "1985-09-09", 15300, -5, True, ""),
    ("Kavya Iyer", "9876500207", "SF-LN-20519", "1995-12-21", 7100, -11, True, "Said she lost her job in October"),
    ("Imran Qureshi", "9876500208", "SF-LN-20537", "1981-04-04", 11000, 5, True, ""),
    ("Neha Joshi", "9876500209", "SF-LN-20544", "1989-08-15", 5600, 1, False, "No consent form on file"),
    ("Vikram Rao", "9876500210", "SF-LN-20561", "1983-02-27", 13250, 2, True, "Asked by letter not to be called"),
]
DO_NOT_CALL = {"Vikram Rao"}
# Only the loans scripts and the one for people who ring in; the CRM's generic built-in scripts stay drafts.
APPROVE = {"EMI due reminder", "Overdue follow-up", "Incoming calls"}


def call(method: str, path: str, body=None, token: str | None = None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request(BASE + path, data=data, method=method)
    req.add_header("Content-Type", "application/json")
    if token:
        req.add_header("Authorization", "Bearer " + token)
    try:
        with urllib.request.urlopen(req, timeout=30) as r:
            raw = r.read()
    except urllib.error.HTTPError as e:
        sys.exit(f"{method} {path} failed: HTTP {e.code} {e.read().decode(errors='replace')[:300]}")
    except urllib.error.URLError as e:
        sys.exit(f"Cannot reach the demo server at {BASE} ({e.reason}). Start demo/start-demo-crm.cmd first.")
    return json.loads(raw) if raw else None


def main() -> None:
    sent = call("POST", "/api/auth/otp/request", {"phone": ADMIN_PHONE})
    if not sent.get("shownOnScreen") or not sent.get("codeOnScreen"):
        sys.exit("This server does not show sign-in codes on screen: it is not the demo copy. Nothing was changed.")
    token = call("POST", "/api/auth/otp/verify", {"phone": ADMIN_PHONE, "code": sent["codeOnScreen"]})["accessToken"]

    overview = call("GET", "/api/voice/overview", token=token)
    if overview["platform"] != "simulated" or overview["live"]:
        sys.exit(f"The server's calling platform is '{overview['platform']}', not simulated. Nothing was changed.")
    if call("GET", "/api/contacts?size=1", token=token)["items"]:
        sys.exit("The demo CRM already has contacts. Restart demo/start-demo-crm.cmd for an empty one.")

    call("PUT", "/api/settings/branding", {
        "companyName": COMPANY, "agentName": AGENT, "brandColor": "#0f766e", "supportPhone": "",
        "contactLabel": "Customer", "loginHeadline": "Sunrise Finance customer desk",
        "loginText": "Sign in with your registered mobile number."}, token)
    print("Branding: done")

    applied = call("POST", "/api/settings/packs/finance/apply", token=token)
    print("Starter pack 'Loans and EMI collections':", applied)

    for faq in call("GET", "/api/faq", token=token):
        answer = FAQ_ANSWERS.get(faq["question"], faq["answer"])
        if "[" in answer:
            continue                       # still a placeholder: leave it switched off
        call("PUT", f"/api/faq/{faq['id']}", {"question": faq["question"], "answer": answer,
                                              "keywords": faq["keywords"], "sortOrder": faq["sortOrder"],
                                              "active": True}, token)
    print("Knowledge base: filled in and switched on")

    approved = []
    for s in call("GET", "/api/voice/scripts", token=token):
        if s["name"] in APPROVE and not s["approved"] and "[" not in s["openingLine"] + s["systemPrompt"]:
            call("POST", f"/api/voice/scripts/{s['id']}/approve", token=token)
            approved.append(s["name"])
    print("Scripts approved:", ", ".join(approved))

    keys = {f["label"]: f["key"] for f in call("GET", "/api/fields", token=token)}
    today = dt.date.today()
    for name, phone, loan, born, emi, due, consent, note in CUSTOMERS:
        fields = {keys["Loan account number"]: loan, keys["Date of birth"]: born, keys["EMI amount"]: str(emi),
                  keys["Due date"]: (today + dt.timedelta(days=due)).isoformat()}
        if due < 0:
            fields[keys["Overdue amount"]] = str(emi)
            fields[keys["Days past due"]] = str(-due)
        contact = call("POST", "/api/contacts", {"fullName": name, "phone": phone, "notes": note or None,
                                                 "source": "Demo", "fields": fields}, token)
        if consent:
            call("POST", f"/api/contacts/{contact['id']}/voice-consent", {
                "alternate": False, "aiCallConsent": True, "recordingConsent": False, "source": "SIGNUP_FORM",
                "evidenceRef": "Loan application form (demo)"}, token)
        if name in DO_NOT_CALL:
            call("POST", f"/api/contacts/{contact['id']}/status", {"status": "DO_NOT_CALL"}, token)
    print(f"Customers: {len(CUSTOMERS)} added")

    detail = call("POST", "/api/voice/campaigns", {
        "name": "EMI due this week", "scriptName": "EMI due reminder", "statuses": None,
        "fieldKey": keys["Due date"], "fieldOp": "DUE_WITHIN_DAYS", "fieldValue": "7",
        "maxConcurrent": 2, "maxAttempts": 3, "retryGapMin": 120}, token)
    preview = detail["preview"] or {}
    print(f"Campaign '{detail['campaign']['name']}': created, not started "
          f"({len(detail['targets'])} customers due within 7 days; can be called now: {preview.get('eligibleNow')}, "
          f"skipped: {preview.get('skipped')}, waiting: {preview.get('waiting')})")
    print(f"\nReady. Open http://localhost:3094 and sign in with {ADMIN_PHONE}; the code is shown on the page.")


if __name__ == "__main__":
    main()
