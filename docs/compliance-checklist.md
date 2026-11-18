# Compliance checklist before going live

This list is for the company taking over the CRM and their compliance adviser. It sets out what the software already
does, what it does not do, and what only the company can decide or register. **It is not legal advice.** The laws named
here (TRAI's telemarketing rules and DLT registration, the Digital Personal Data Protection Act, 2023 and its Rules) are
named so the adviser knows what to check, not to say what they require.

Tick every line in sections 2 and 3 before the first campaign to real customers.

## 1. What the CRM already enforces

Every rule below is checked by the code, and tested, before each number is dialled
(`backend/.../voice/CallEligibility.java`). A number that fails any one of them is not called.

| Rule | How it works | Can staff override it? |
|---|---|---|
| Consent | A number is called only if consent to AI calls is recorded for it: on the contact page, or for a whole imported list with a note of where the consent came from. No record, or a refusal, means no call. | No. Consent must be recorded first. |
| Opt-out | A caller who says "stop calling", "don't call me again", "remove my number", "take me off your list" and similar is opted out: every consent for that number is withdrawn, and if they said "all calls" the contact is also marked **Do not call**. If the AI records something else, or the caller hangs up before it replies, the CRM records the opt-out itself from the caller's own words, including words said as they hang up or over the agent's goodbye. Staff can withdraw consent on the contact page. An uploaded list marked "everyone agreed" does not undo an opt-out or a refusal: their consent is not recorded and the import report lists them by row. Call-backs already booked for them: "all calls" cancels them; AI calls only keeps them (they may still want the person they asked for) with a warning at the top and an alert to read the transcript before calling. A contact marked **Do not call** any other way (the contact page, a bulk stage change, merging duplicates) has its open call-backs closed too, and whoever had taken one is told; changing the status back does not reopen them. | No. An opted-out number is called again only if staff record a new consent by hand on the contact page, after the person agrees again. |
| Do not call | A contact with the status **Do not call** is never dialled, whatever the consent says. | Only by changing the status by hand. |
| Do-not-disturb (DND) | Numbers marked BLOCKED by the scrubbing service are skipped, with each result kept for 24 hours. **But no scrubbing service is connected yet** (see 2.3): today every number comes back "not known" and is called. The campaign page says so. | Not applicable until a service is connected. |
| Calling hours | Only between 09:00 and 18:00 Indian time by default (9 AM to 6 PM; staff desk hours, 09:30 to 18:30, are separate) (`VOICE_WINDOW_START`, `VOICE_WINDOW_END`). A campaign can narrow this window but never widen it. | Only the server settings can change it. |
| How often | At most 2 calls to the same number per day, at least 2 hours apart, and at most the campaign's number of attempts. | Only the server settings can change it. |
| Still needed | Just before dialling, the CRM checks that the call is still needed (for example the person has not paid since the campaign started). | No. |
| AI says it is an AI | Every starter opening line introduces the agent as an AI assistant ("Hello, this is Asha, an AI assistant calling from ..."), and the AI's instructions say it is not a human and must say so. If a caller asks whether it is a machine, it says it is an AI. | The opening line is the company's own text; keep the AI disclosure in it. |
| Approved scripts only | A real call uses only a script version an admin has approved. Each approval is recorded in the audit log (Administration, **Audit log**: the admin can read who did what and when, and nobody can change or remove an entry). | Only admins approve. |
| What the AI may say | Each contact field is marked "To anyone", "Only after verified" or "Never". The AI cannot read out "Never" fields, and the others only as marked. Before the identity check, our own model is stopped from naming the reason for the call (words such as overdue, EMI, loan, fee, appointment, check-up, course, admission, policy, renewal, property, site visit). | Admins set this per field. |
| Identity check | Before personal details, the caller must give their full name and an identity answer (for example customer ID). Three failures end the AI's part and pass the call to a person. | No. |
| A person when needed | Complaints, distress, a stuck AI or a fault hand the call to a person: a live transfer when the desk is open, otherwise a call-back task. | No. |
| Keeping transcripts | Call transcripts are deleted after 365 days (`VOICE_RETENTION_DAYS`); the outcome of each call stays. | Only the server setting can change it. |
| Erasing a person | When someone asks for their data to be deleted, the admin opens the contact, presses **Erase this person** and types their phone number and a reason. At once and for good: their details, notes, timeline, follow-ups, call log, consent records, call-backs, alerts, campaign places and do-not-disturb results are deleted; their AI calls stay only as numbers for the reports (when, how long, how it ended) with the number, transcript, summary, notes and the phone company's call id removed; upload reports that named them lose the name. Refused while a call with them is live. If they had said no to AI calls (opted out, refused, or "Do not call"), only a one-way hash of the number is kept, so a list imported later cannot make it callable again; only consent recorded by hand on the contact page afterwards counts. The erasure is in the audit log, without their name. | Only the admin can erase. It cannot be undone. |
| Audit trail | Script approvals, consent changes, every AI tool use and staff actions are recorded with who and when. | No. |
| Call recordings | Real AI calls whose opening line says "This call may be recorded" are recorded and kept on the CRM server with the transcript, at least 120 days (365 by default). Only the admin and staff given "Can listen to call recordings" can play them; every listen is audited. | Who may listen: yes (Staff users). How long: the server settings, never under 120 days. |
| Warnings | Admins get an urgent alert when 5 or more people opt out within 24 hours, and when the month's estimated call cost reaches the limit (`VOICE_MONTHLY_CAP_INR`). The cost alert does not stop calls. | Not applicable. |
| Stop everything | **Pause all** on the AI voice agent page stops all dialling at once. | Any admin. |

## 2. What the CRM does not do yet

Each item here needs a decision, a service or a change before going live.

**2.1 Erasing a person's data: what the CRM cannot reach.** The CRM erases a person itself (section 1), but not the
copies kept elsewhere: database backups, server logs, the phone company's own records of the calls (Plivo keeps the
number, time and length of each call), spreadsheets exported earlier, and anything staff wrote about the person on
another contact. An upload report keeps a name only if it was spelled differently from the contact's name at the time
of the erasure. The speech engine's outbox (`voice-engine/README.md`, "The outbox") holds a caller's last words only
while the CRM cannot be reached, at most 7 days, and deletes each as soon as the CRM has it; one that arrives after
the person was erased is refused by the CRM and deleted, so nothing about them is written back.

A person who asks what is kept about them (an access request): since 17 November 2026 the admin presses **Download
their data** on the contact's page and gets one file with everything the CRM holds about them, from the same places
an erasure deletes from (details, custom fields, tags, timeline, follow-ups, call log, consent records, AI calls with
transcripts, call-backs, campaign places, do-not-disturb results, staff alerts, changes to their record). Staff
notes and alerts are in it, so the admin reads it before sending it; each download is in the audit log. Not in it:
the copies outside the CRM listed above, upload reports, and what staff wrote about them on another contact.

- [ ] Decided with the adviser: how long backups and server logs are kept, and how an erasure request also reaches
  Plivo and anyone holding exported spreadsheets
- [ ] Decided with the adviser: what of the downloaded file is sent to a person who asks (staff notes, alerts), and
  how it is sent to them safely

**2.2 Call recording.** Since 17 November 2026 every real AI call is recorded, both voices, **when its script's
opening line tells the person "This call may be recorded"**; a call is never recorded without being told. The starter
opening lines say it again (new installations, and draft scripts in an existing CRM, were updated; a script already
approved keeps its wording until the admin approves a new version, and until then its calls are not recorded). The
Scripts page flags an opening line that does not say it, including one already approved, and approving such a version
asks again. Test-console calls are never recorded.

- Where: one audio file per call (Opus, about 120 kB a minute) in a folder on the CRM server
  (`VOICE_RECORDINGS_DIR`, default `data/recordings` next to the CRM). The database notes which call it belongs to.
- How long: with the transcript, `VOICE_RETENTION_DAYS` (365 now), then deleted automatically. The CRM refuses to
  start with fewer than 120 days. Erasing a person deletes their recordings at once.
- Who can listen: the admin, and staff the admin ticks "Can listen to call recordings" (Administration, Staff users),
  and those only for calls they can see (their own contacts'). Every listen is in the audit log ("Listened to a call
  recording"). Taking the tick away works at once.
- A person's data copy ("Their data", Download) says which of their calls have a recording; the admin can play it
  and give them a copy on request.

- [ ] Every approved script's opening says "This call may be recorded" (no amber note on the Scripts page), or the
  calls without it are knowingly not recorded
- [ ] The adviser has confirmed that announcing the recording at the start of the call is enough for this business
  (or what else is needed)
- [ ] The recordings folder is included in the server's backups, and the backups are kept no longer than needed
- [ ] Who may listen is decided, and only those staff have the tick

**2.3 Do-not-disturb scrubbing.** No DND service is connected, so the CRM cannot skip numbers on the national
do-not-disturb register. The code has a place to plug one in (`DndProvider`); the phone provider may offer one.

- [ ] Adviser says whether each kind of call (reminder, follow-up, collection, enquiry) must be scrubbed against DND
- [ ] If yes: a DND service chosen and connected before those campaigns start

**2.4 Sending sign-in codes.** Staff sign-in codes are sent by SMS through the company's own MSG91 account
(`docs/new-customer-setup.md`); until that is set up they are shown on the screen, and the CRM must not be put on the
internet. The code is never written to the log; the log shows only the last four digits of the number.

- [ ] MSG91 account, DLT sender ID and sign-in template registered in the company's name
- [ ] A real sign-in code received by SMS, then `OTP_SHOW_ON_SCREEN=false`

**2.5 The reason for the call in opening lines.** The starter openings no longer name the reason (they used to say
"your check-up is due", "your upcoming appointment", "your site visit", "your policy renewal", "you recently
enquired"): they say who is calling, that it is an AI assistant, and ask for the person by first name; the reason comes
after the identity check. The check that stops our own model naming the reason before the identity check now
knows the other starter packs' reasons as well as money words (appointment, check-up, doctor, course, class, admission,
exam, insurance, policy, renewal, claim, property, visit, flat, plot and similar; 16 November 2026). The company's own
openings and identity questions can still name a reason (asking for a "policy number" says there is a policy); since
16 November 2026 the Scripts page flags both with the same word list, and approving a flagged opening asks again.
The finance and insurance starter packs' identity questions ("Loan account number", "Policy number") are flagged
this way. What remains: a word outside the list is neither stopped nor flagged, and a flag does not stop anything.

- [ ] Each flagged identity question renamed (Custom fields, e.g. "Account number") or kept with the adviser's
  agreement

- [ ] Every opening line reviewed by the adviser for what it reveals to someone who is not the customer

## 3. What only the company can decide or register

These are outside the software. Nothing in the code assumes an answer.

**3.1 Telephony and TRAI**

- [ ] The company's own Plivo account (or other licensed provider), KYC finished, **India data region** chosen at
  sign-up (it cannot be changed later)
- [ ] DLT registration, if the adviser says the calls need it, and the right kind of number for each kind of call
- [ ] Each kind of call classified with the adviser (for example service or transactional versus promotional), because
  this decides the number series, DND scrubbing and calling hours
- [ ] Calling hours confirmed; the CRM's 09:00 to 18:00 changed if the adviser says otherwise

**3.2 Consent**

- [ ] For every list to be called: how consent to AI calls was collected, and proof that it was (consent form, app
  tick box, signed form), recorded in the CRM with the list
- [ ] The wording customers agreed to covers calls by an AI assistant, not only by staff
- [ ] How a customer withdraws consent outside a call (phone, email, branch), and who records it in the CRM

**3.3 Personal data (DPDP Act)**

- [ ] A privacy notice that tells customers what is kept, why, for how long and how to complain
- [ ] Where the CRM, its database and the voice engine are hosted (city, provider), and who at the hosting company can
  reach them
- [ ] Which AI brain is used. **Our own model** keeps conversations on the company's machine. **Claude** sends each
  conversation to Anthropic in the USA; if Claude is used, check the adviser is content with this and with
  Anthropic's data terms
- [ ] Plivo's data terms (call audio passes through Plivo)
- [ ] How long to keep contacts, notes and call outcomes (transcripts: 365 days unless changed)
- [ ] Who in the company may see contacts, export them (admins and managers only) and approve scripts
- [ ] What to do if data leaks: who is told, and how fast
- [ ] How erasure and access requests are answered (see 2.1)

**3.4 Scripts and staff**

- [ ] Every script and FAQ answer the AI uses read and approved by the company (the starters are generic drafts)
- [ ] A named person who reads a sample of transcripts every week, and the "flagged as wrong" list
- [ ] Desk hours and the desk number for live transfers set; call-back tasks have an owner
- [ ] A pilot of 20 to 50 contacts, with every transcript read, before any larger campaign

## 4. Sign-off

| | Name | Date |
|---|---|---|
| Company owner | | |
| Compliance adviser | | |
| Person who reads transcripts | | |
