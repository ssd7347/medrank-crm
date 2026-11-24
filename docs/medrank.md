# MedRank edition

This is the AI Call CRM (tag `common-crm-v1` in `ssd7347/ai-call-crm`) set up for MedRank, a NEET UG
admission-counselling company. It keeps everything the common CRM has, including the real AI phone calls (Plivo, our
own speech engine and brain). Three things are added:

- **The "NEET UG counselling (MedRank)" starter pack** (`backend/src/main/resources/packs/neet_counselling.json`),
  listed first. It adds:
  - **Stages**: Counselling session booked, Registered for counselling, Choices filled, Seat allotted, Admitted.
  - **Custom fields**, with what the AI agent may say about each:

    | Field | The AI agent |
    |---|---|
    | Roll number (the NEET roll number) | never says it; the caller may prove who they are with it ("Ask to confirm"); its name leaves out "NEET" because the AI asks for it by name before the check |
    | Parent name, Category, Domicile state | never sent to the AI |
    | Course wanted, NEET score, NEET All India Rank, Counselling round, Choice filling deadline, Documents pending, Fee due, Fee due date, Reporting deadline | only after the caller has proved who they are |

  - **Call scripts** (drafts until approved): NEET enquiry follow-up, Counselling round reminder, Documents reminder,
    Counselling fee reminder, Reporting reminder after allotment. Each says it is an AI and that the call may be
    recorded, and nothing about why it calls until the identity check. The agent never promises or predicts a
    seat, college or cut-off, never advises which college to choose (a counsellor does), and hands anything about
    refunds, upgrading, resigning or exiting a seat to a person.
  - **Knowledge-base answers** (switched off until filled in): what MedRank does, the counselling fee, "Can you
    guarantee a seat?" (no), the next counselling round, the documents needed, and how to book a counsellor.
- **The reason guard knows NEET calls.** Before the identity check, the agent may not say NEET, counselling,
  choice filling, an allotted seat, a rank, a score or cut-off, a college, MBBS or BDS, or documents, unless the
  opening, an identity question or the caller used the word first (`FactGuard.REASON_WORDS`). The company's name
  "MedRank" does not count as "rank".
- **The browser-tab icon is MedRank's shield** (`frontend/src/app/favicon.ico`, cropped from the MedRank Academy
  logo). The logo in the menu and on the login page is a setting, not code: upload the shield in **Company
  settings** (PNG, JPEG or WebP, up to 300 KB). The full logo with its words is too small to read there.

## Setting it up

Follow `docs/new-customer-setup.md`, with these choices:

1. **Company settings**: company name MedRank, the agent's name, the brand colour, contacts called "Student".
2. **Starter pack**: load "NEET UG counselling (MedRank)".
3. **Fill in the [placeholders]** in the six answers (services, packages and fees, round dates, the documents list,
   how to book) and switch them on. Check the round dates against the official MCC and state counselling notices
   before each round.
4. **Rewrite the five scripts** in MedRank's own words, have the compliance adviser read the opening lines, and
   approve them.
5. **Import** the lead lists (Excel or CSV). Record consent to AI calls only for lists whose students or parents
   agreed to be called by an AI assistant (`docs/compliance-checklist.md`, 3.2). Put NEET details in their fields:
   a NEET PG candidate's details do not belong in the NEET UG score and rank fields.
6. **First real call and pilot**: `docs/first-real-call.md`, then a pilot of 20 to 50 students with every
   transcript read.

## Not changed

The brain was trained on the common CRM's kinds of calls (clinic, loans, education, insurance, property), not on
NEET counselling. Test every NEET script in the **Test console** before approving it. The training kit can make
NEET practice calls (`training/callkit.py` knows the pack) for a later round of training.
