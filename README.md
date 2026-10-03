# AI Call CRM

A CRM with an English-speaking AI phone agent, built to be installed separately for each customer company and
branded as theirs. Contacts come in from the customer's own Excel or CSV files, with whatever columns those files
have; the AI agent calls them (payment reminders, appointment reminders, enquiry follow-ups, feedback surveys),
answers simple questions, and hands over to a person whenever it should.

It started from the MedRank counselling CRM (that project's tag `counselling-crm-v1`), with every
counselling-specific module removed.

## What is in it

- **Contacts** with the standard details (name, phone, alternate phone, email, status, source, owner, branch, notes)
  plus **custom fields** for everything else a customer keeps (amount due, due date, customer ID, plan...).
  Timeline, follow-ups, staff call logging, CSV export.
- **Import from Excel** (.xlsx, .xls) or CSV: the CRM reads the headings, guesses what each column is, the person
  confirms, and new columns become custom fields automatically. A phone number already in the CRM updates that
  contact instead of duplicating it; bad rows are reported by row number. Consent to AI calls can be recorded for
  a whole list that agreed to it.
- **AI voice agent**: versioned call scripts that the admin approves, campaigns that pick contacts by status, by
  upload and by a field condition ("Due date within the next 7 days"), calling hours in Indian time, retries,
  a "Pause all" switch, identity checks against the customer's own fields, field-level control of what the AI
  may say, call-backs for staff, transcripts, metrics and a test console.
- **White-label settings**: company name, logo, the AI agent's name, brand colour, login-page wording, and what
  contacts are called ("Customer", "Patient", "Lead"...). The login page, menu and calls all follow them.
- **Customisable stages**: each customer names, colours and orders the steps contacts move through.
- **Industry starter packs** (clinic, loans and EMI, real estate, education, insurance): stages, fields, call
  scripts and answers for that kind of business in one click. They live in `backend/src/main/resources/packs`,
  so a new industry is a new JSON file.
- **Feature switches** (`FEATURES`): sell plans with or without AI calls, imports, exports and follow-ups from
  the same code.
- **Sign-in**: one admin; staff (managers and agents) are created by the admin; mobile number + one-time code;
  signed out after 3 minutes without activity.

No telephony or voice-AI provider is connected yet, so the agent runs in **simulated mode**: everything works,
but no phone rings. Connecting one is a single adapter class plus settings: see
[docs/voice-agent.md](docs/voice-agent.md).

## Roles

| Role | Can |
|---|---|
| Admin (exactly one) | Everything: staff, branches, custom fields, company settings, scripts, campaigns, test console |
| Manager | Every contact, imports and exports, the AI knowledge base, all call-backs |
| Agent | Their own contacts and unassigned ones, their follow-ups and call-backs |

## Stack

| Part | Tech |
|------|------|
| `backend/` | Java 21, Spring Boot 4.1, Spring Security (JWT + rotating refresh cookie), JPA/Hibernate, Flyway, PostgreSQL, Apache POI |
| `frontend/` | Next.js 16 (App Router), React 19, TypeScript, Tailwind CSS 4 |

The browser only talks to the Next.js origin; Next proxies `/api/*` to the backend (`BACKEND_URL`).

## Run locally

Prerequisites: JDK 21, Maven, Node 20+, PostgreSQL.

1. Create the database: `createdb -U postgres ai_call_crm`
2. Backend settings: copy `backend/.env.example` to `backend/.env` and fill it in (database password, a JWT
   secret, the admin's email, password and mobile number).
3. Backend: `cd backend && mvn spring-boot:run` (port from `PORT`, default 8080).
4. Frontend: `cd frontend && npm install && BACKEND_URL=http://localhost:8080 npm run dev`
5. Open http://localhost:3000 and sign in with the admin's mobile number. While `OTP_SHOW_ON_SCREEN=true`
   (development only) the code is shown on the login page.

`docs/sample-contacts.csv` is a small file to try the import with.

## Tests

`cd backend && mvn test` runs the unit and integration tests (H2 in-memory database): sign-in, contacts and
custom fields, Excel/CSV import and export, white-label settings, and the AI agent (Tool API, verification,
hand-overs, opt-out, campaigns, the kill switch, scripted and red-team conversations, and a fake provider plugged in).

## Selling it to a new customer

See [docs/new-customer-setup.md](docs/new-customer-setup.md).
