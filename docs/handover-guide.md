# Hand-over guide: AI Call CRM

For the company taking over this CRM: the owner (the one admin), the staff who use it, and whoever looks after the
machines it runs on. Written 15 November 2026, for the hand-over around 23 November 2026.

Plain words first; the technical details are in the documents linked at the end.

## 1. What you are getting

A CRM (your list of customers, with their details, follow-ups and history) with an **AI phone agent** that speaks
English. The agent calls your contacts for routine things (payment reminders, appointment reminders, follow-ups,
feedback), answers simple questions from your own answers, checks who it is talking to before saying anything
private, and hands the caller to one of your people whenever it should. It carries your company's name, logo and
colours, and the agent has the name you give it.

It is made of these parts:

| Part | What it does | Where it runs |
|---|---|---|
| The CRM website | What your staff use in the browser | With the CRM server |
| The CRM server and its database | Keeps your data, decides who may be called and when, runs the calls' safety rules | Your server (for now the developer's laptop) |
| The AI brain (`ourvoice8b-r4`) | Decides what the agent says. Our own trained model: no fee per call to an AI company | A computer with a graphics card (GPU) |
| The speech engine | Turns the caller's voice into text (Whisper) and the agent's reply into a voice (Piper) | Best on the same GPU computer |
| The gateway | The only part the internet can reach: it lets the phone company through and nothing else | With the CRM server |
| The phone line (Plivo) | Rings the phone and carries the call's sound | Plivo's service, on **your** Plivo account |

## 2. What is ready, and what is not yet

**Ready and tested** (more than 200 automatic tests, and simulated calls):

- Contacts, custom fields, tags, follow-ups, Excel/CSV import and export, duplicates, staff roles, branches,
  stages, your branding, industry starter packs, erasing a person who asks for their data to be deleted.
- The AI agent's rules: consent, calling hours, retries, identity checks, private fields, call-backs, hand-over to a
  person, opt-out ("stop calling me"), "Pause all", transcripts and the test console.
- The brain: on 59 practice calls with callers it had never seen it handled 54 well (92%), and over three such sets
  150 of 177 (85%); it never gave a private detail before the identity check.
- The phone connection to Plivo and the speech engine, tested on one computer with fake phone calls.

**Not done yet, and needed before real customers are called:**

| What | Why it matters | Whose |
|---|---|---|
| A real test call on a real phone line | Nothing has been tried on a real line yet; the first call is to the developer's own phone | Needs the next two |
| Your Plivo account | The phone line; your own KYC, number and (in India) DLT registration | **Yours** |
| A GPU computer | The brain is too slow on an ordinary computer (6.5–21 s per reply); on a GPU about 1 s | **Yours** (paid, monthly or by the hour) |
| Your MSG91 account for login codes | The CRM sends sign-in codes by SMS through MSG91 (built, tested against a stand-in). Until your account, DLT sender ID and template are set up, the code is shown on the login page, so the CRM website must **never** be put on the internet before that | **Yours** (`docs/new-customer-setup.md`) |
| Do-not-disturb (DND) checks | Numbers are not checked against the national DND register; only people who gave consent are called | Your telephony provider's scrubbing service |

## 3. Your decisions before going live

Tick these off with the developer:

1. **Plivo account in your company's name**: India data region (it cannot be changed later), KYC, an Indian
   number. Ask Plivo what outbound calls to Indian mobiles need for your kind of calls (DLT registration, a
   160-series number). Only you or your developer enter the account's keys, on the server; never by email or chat.
2. **The GPU computer**: a T4-class GPU, in India if possible (every extra delay on the line is heard). By the hour
   for the first test calls, monthly once live.
3. **Hosting**: where the CRM server and database run once they leave the developer's laptop.
4. **MSG91 account** for login codes by SMS, with DLT registration of a sender ID and a sign-in template
   (see section 2). Plivo does not send SMS within India.
5. **Compliance**, with your adviser (the full list, with what the CRM already enforces: `docs/compliance-checklist.md`): that everyone you call agreed to be called and how (a form, a contract
   clause); the legal calling hours (the CRM's default is 09:00–18:00 Indian time); DND; what the opening lines
   say. Nothing in the software assumes these answers.
6. **Your desk**: the phone number of the staff desk for live transfers, and its hours (default 09:30–18:30,
   Monday to Saturday). Without a desk number every hand-over becomes a call-back task. The desk hours also decide
   call-back times: a caller who asks for one inside them is promised that time ("on Tuesday at 4:00 PM"), and the
   task is due then; otherwise within two hours of desk time.

## 4. Using it every day

**Signing in.** Mobile number, then a one-time code. You are signed out after 3 minutes without activity.

**Who can do what.**

| Role | Can |
|---|---|
| Admin (exactly one) | Everything: staff, branches, custom fields, stages, company settings, starter packs, scripts, campaigns, test console, "Pause all", erasing a person |
| Manager | Every contact, imports and exports, the AI knowledge base, all call-backs |
| Agent | Their own contacts and unassigned ones, their follow-ups and call-backs |

**The menu.**

- **Dashboard**: contacts, follow-ups due, call-backs waiting, AI calls today, contacts by stage.
- **Contacts** (called whatever you set, e.g. Customers or Patients): search, filter, tick several for bulk actions
  (assign, change stage, add or remove a tag). Each contact's page has their details, timeline, follow-ups, AI
  calls and **consent for AI calls**. Nobody is called by the AI without consent recorded. When someone asks for
  their data to be deleted, the admin presses **Erase this person** at the bottom of their page (it cannot be undone;
  see `docs/compliance-checklist.md`, section 1 and 2.1). When someone asks what you keep about them, the admin
  presses **Download their data** just above it: one file with everything the CRM holds about them. Read it before
  sending it, as it includes staff notes and alerts.
- **My follow-ups**: your reminders; the bell shows one when it falls due.
- **Import from Excel** (managers and admin): upload your file, check the guessed columns, import. A number already
  in the CRM updates that contact instead of adding it twice; bad rows are listed by row number. You can record
  consent for a whole list that agreed to AI calls; it never overrules someone who had asked to stop or said no
  (they are listed in the report, and only a consent recorded by hand on their page counts).
- **AI voice agent**:
  - *Overview*: whether calling is Live, Simulated or Paused, the calling hours, call-backs waiting and overdue,
    and how the calls are going. **Pause all** (admin) stops every campaign at once and sends incoming callers to
    the normal greeting. Use it whenever something looks wrong; "Resume calling" undoes it.
  - *Call-backs*: people the agent promised a call from a person. Call them and mark each one done. Complaints and
    distress come in marked urgent. One that starts "CHECK BEFORE CALLING" means the caller said something later
    (for example "no need to call me back"): read the call's transcript first, and mark it done if they really don't want the call.
  - *Calls*: every AI call with its transcript and what the agent did. If the agent got something wrong, press
    **Flag as wrong** and say what: flagged calls are counted on the Overview so they get looked at.
  - *Campaigns* (admin): who to call (by stage, by upload, by a field such as "due date within 7 days"), with a
    preview of who will be called and why others will not; start, pause, finish.
  - *Scripts* (admin): what the agent is calling about and how it opens. Every save makes a new version, and a
    version is used on calls only after the admin approves it (approving retires the previous one). An amber note
    under an opening line means it says why you are calling (for example "your policy renewal") before the AI knows
    it is talking to the right person, or that the call is recorded (it is not); an amber "Identity question" note at
    the top means a field's name says why you are calling.
    Change them, or approve only if your compliance adviser agrees.
  - *Test console* (admin): talk to the agent as if you were the contact, with no effect on the contact at all.
- **Admin** pages: Staff users, Branches, Custom fields, Stages, AI knowledge base (the only answers the agent
  gives to questions), Company settings, Industry starter packs, Connected services, and the **Audit log**: who
  did what and when (sign-ins and failed ones, changes to contacts, consent, scripts, campaigns, data downloads and
  erasures), filtered by action, staff member and dates. Entries cannot be changed or removed. Each contact's
  "Their data" card links to that contact's own history.

**Custom fields and privacy.** For each field you choose what the agent may say: *Never read out* (anything used
only to check identity, such as customer ID or date of birth), *Only after the caller is verified* (amount due,
appointment time), or *To anyone who answers* (only harmless details). Tick **Ask to confirm** on one or two fields
the real person would know (for example a customer ID): the agent uses them to check identity. It asks for them by
the field's name before it knows who answered, so the name should not say why you are calling ("Account number",
not "Loan account number").

## 5. What a call is like

- The agent says the script's opening line, with the contact's details filled in. The starter openings introduce it
  as an AI assistant ("Hello, this is Asha, an AI assistant calling from ..."); keep that in your own wording.
- **Before anything private**, it checks identity: the person's full name and one "Ask to confirm" field. Names are
  matched the way a phone line hears them (Surish for Suresh passes, Rajesh for Ramesh does not). Three failed
  checks end the attempt and hand the caller to a person. Until the check passes, the agent does not even say what
  the call is about (words such as loan, EMI, overdue or amount are stopped); **your opening line is the
  exception**, so keep it neutral.
- It answers questions only from your AI knowledge base, books call-backs ("call me at 4"), records the outcome on the
  contact (acknowledged, promised to pay, interested, wrong person...), and stops AI calls when asked ("stop calling
  me"); someone who wants no calls at all is also marked "Do not call".
- **Hand-over to a person**: a live transfer to your desk during desk hours (once a desk number is set), otherwise a
  call-back task. The caller first hears "I am connecting you to a member of our team now"; if the transfer then
  fails, or they hang up before it, an urgent call-back is booked so they still get a person. If the brain fails or
  keeps repeating itself, the call is handed to a person the same way.
- **Calling rules**, checked just before every call: consent, not marked "Do not call", within calling hours, at
  most 2 calls a day to the same number and 2 hours apart, and at most the campaign's number of attempts (1 to 5,
  default 3).

**Known limits today**: replies are expected to take about 3 seconds on the line (the target is about 2); the agent
speaks with a US English voice; Indian names and some words can be misheard (the CRM sends the contact's name to
the speech engine so it spells it right, which works with the better ear, `small.en`, on the GPU).

## 6. Going live safely, step by step

1. Load the starter pack closest to your business, then **rewrite the scripts and knowledge-base answers in your own
   words** (they arrive as drafts with [placeholders]). Have your compliance adviser read the opening lines.
2. Set up custom fields with the right privacy choice and "Ask to confirm" (section 4).
3. In the **Test console**, play the hard cases: the wrong person answers, "I am busy", "stop calling me", a
   complaint, someone upset, someone trying to trick it.
4. **First real call**: to the developer's own phone, then to your team's phones. Read every transcript.
5. **Pilot**: one campaign of 20 to 50 contacts who agreed to be called. **Read every transcript** before calling
   more.
6. Then grow slowly, and keep reading the flagged calls and the "Why handed over" list on the Overview.

## 7. Running it (for whoever looks after the machines)

**Never put the CRM server or the speech engine on the internet directly.** Only the gateway is reachable from
outside, and it lets through nothing but the phone company's three requests and the call's sound stream (everything
else gets "not found"). Staff use the CRM website on the office computer or through a private connection until real
login codes are sent.

**The secrets** (passwords, the Plivo keys, the shared signing secret) live in the server's settings file
`backend/.env` or as environment variables, never in the code, email or chat. The list of settings with
explanations is `backend/.env.example`; the speech engine's are at the top of `voice-engine/engine.py` and in
`voice-engine/README.md`.

The settings that matter most for live calls:

| Setting | Value |
|---|---|
| `VOICE_PLATFORM` | `plivo` (`simulated` until the line is connected: everything works but no phone rings) |
| `PLIVO_AUTH_ID`, `PLIVO_AUTH_TOKEN`, `PLIVO_FROM_NUMBER` | From your Plivo account |
| `PLIVO_PUBLIC_URL`, `PLIVO_ENGINE_URL` | The gateway's public address (`https://...` and `wss://.../call`) |
| `VOICE_HMAC_SECRET` | 32+ random characters, the same for the CRM server and the speech engine |
| `AI_BRAIN`, `LOCAL_LLM_MODEL` | `LOCAL` and `ourvoice8b-r4` |
| `STT_MODEL` (speech engine) | `small.en` on the GPU computer (it hears Indian English much better than the default `base.en`) |
| `VOICE_DESK_NUMBER`, `VOICE_DESK_OPEN`, `VOICE_DESK_CLOSE` | Your desk's number and hours |
| `VOICE_WINDOW_START`, `VOICE_WINDOW_END` | Legal calling hours, Indian time (default 09:00 and 18:00, that is 9 AM to 6 PM) |
| `VOICE_RECORDINGS_DIR` | Folder on the CRM server for call recordings (default `data/recordings`) |
| `VOICE_RETENTION_DAYS` | Days transcripts and recordings are kept (default 365; never fewer than 120) |
| `OTP_SHOW_ON_SCREEN` | `true` only while nobody outside can reach the website |
| `OTP_SMS_PROVIDER`, `MSG91_AUTH_KEY`, `MSG91_OTP_TEMPLATE_ID` | `msg91`, your MSG91 key and sign-in template id (`docs/new-customer-setup.md`) |

**Start order**: the database, the CRM server, the speech engine, the gateway; on the GPU computer the brain
(loaded and given one warm-up message). The step-by-step for the first call, with the checks at each step, is in
`docs/first-real-call.md`. In that plan the CRM server reaches the brain through a private SSH tunnel; the brain
is never put on the open internet.

**Backups**: `ops/backup.py` does them; `docs/backups.md` has the details and the lines to schedule it.
- **Every night**, it backs up the database and the recordings folder (`VOICE_RECORDINGS_DIR`) to another disk.
- **Every week**, `check` proves the newest backup restores.
- **Backups are deleted after `BACKUP_KEEP_DAYS`** (default 14), so an erased person's data leaves the backups too.
- **Read its log weekly**: a failed night says `BACKUP FAILED`.

Transcripts and recordings in the CRM itself are deleted automatically after 365 days (`VOICE_RETENTION_DAYS`,
never fewer than 120).

**Call recordings**: every real AI call whose opening line says "This call may be recorded" is recorded (both voices)
and kept as one audio file on the CRM server. The admin can listen on the call's page (**Listen**); other staff only
if the admin ticks "Can listen to call recordings" for them on **Staff users**, and then only for calls they can see.
Every listen is in the audit log. Test-console calls are never recorded.

## 8. When something goes wrong

| What you see | What to do |
|---|---|
| The agent is saying something wrong, or calls should stop now | **Pause all** on the AI voice agent Overview (admin). Then flag the bad calls and tell the developer |
| Callers hear "Sorry, I am having trouble on this line. A member of our team will call you back." | A part failed (the speech engine, or the CRM server). The engine asks the CRM to book that call-back, so it appears under Call-backs, and the call's transcript says "call_cut_off". If the CRM server itself was down, the engine keeps the request (and anything the caller said as they hung up, such as "stop calling me") and sends it again every 30 seconds until the CRM is back; the call-back then appears. The speech engine's `/health` shows how many are still waiting (`"undelivered"`). Check every part is running; the developer can see the reason in the speech engine's log. **Never switch off the speech engine's computer for good while `"undelivered"` is above 0**: those call-backs and stops would be lost |
| A campaign is running but nobody is called | Check: calling is not Paused; it is within calling hours; the contacts have consent recorded; the script is approved; the campaign preview's reasons |
| Long silences on calls | The brain or speech engine is slow: is the GPU computer running, and the brain warmed up? |
| Call-backs pile up as overdue | Someone must call those people; the Overview shows the count in red |
| Cannot sign in | Codes expire; ask for a new one. Being signed out after 3 idle minutes is normal |
| A number keeps getting called that should not | Open the contact: record "Do not call" or withdraw consent; the agent never calls it again |
| Someone asks what data you keep about them | Admin: open the contact, **Download their data**. Read the file, remove anything your adviser says not to send, and send it to them safely (compliance checklist, 2.1) |
| Someone asks for their data to be deleted | Admin: open the contact, **Erase this person**, type their number and a reason (not their name). The backups lose them by themselves after `BACKUP_KEEP_DAYS` (`docs/backups.md`). Deal with exports and Plivo as agreed with your adviser (compliance checklist, 2.1) |
| The database or the server is lost | Pause all calling. Restore the newest backup into a new database and point the CRM at it. Before calling again, re-enter anyone who said stop, was marked Do not call, or was erased since that backup (`docs/backups.md`, "Restoring after a loss") |

## 9. Where the details are

- `README.md`: what is in the CRM, roles, how to run it on a computer.
- `docs/new-customer-setup.md`: installing and branding a copy for a company, loading its data.
- `docs/voice-agent.md`: the AI agent in depth: rules, tools, settings, privacy.
- `docs/first-real-call.md`: the plan and checks for the first real phone call.
- `docs/compliance-checklist.md`: what the CRM enforces, what it does not do yet, and what only you can decide.
- `docs/backups.md`: nightly backups, the weekly restore check, and restoring after a loss.
- `docs/walkthrough-run-sheet.md` and `demo/`: the demo copy and the plan for showing the CRM.
- `voice-engine/README.md`: the speech engine, the gateway, the ear check.
- `training/STATUS.md`: the full history of how the AI was built and tested.
