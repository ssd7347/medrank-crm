# MedRank CRM

CRM for an MBBS/BDS admission counselling consultancy (domestic India, NEET): leads, NEET profiles,
college & seat-matrix data, rank predictor, choice lists, allotment tracking, documents, fees, alerts,
student/parent portal, and more.

Full requirements: [docs/MBBS_CRM_Master_Specification_Complete.pdf](docs/MBBS_CRM_Master_Specification_Complete.pdf)
(plain-text extract: [docs/specification.txt](docs/specification.txt)).

## Status

**Phase 1 (foundation) — built.** Spec 4.1 Leads, 4.2 NEET profile & eligibility, 4.3 College & seat matrix
(with admin approval workflow), staff login/roles, audit log, dashboard.

**Phase 2 (counselling core) — built.** Spec 4.4 rank-based predictor (bands + shortlist), 4.5 counselling
calendar & choice lists (lock with confirmation), 4.6 allotments & freeze/float/withdraw decisions (single +
bulk CSV), 4.9 alert pipeline (in-app notifications, WhatsApp/SMS queue with escalation), round desk.
WhatsApp/SMS runs in **simulated mode** (messages are logged, not delivered) until a provider such as
Gupshup/Interakt or MSG91 is connected by adding a `MessageSender` implementation.

**Phase 3 (operations & compliance) — built.** Spec 4.7 document vault (per-student checklist, upload with
file-type sniffing, verification queue, audited downloads), 4.8 fee plans, instalments, payments, printable
receipts, refunds and commissions, 4.11 staff performance & reassignment, 4.14 helpdesk tickets with SLA,
4.27 refund rules engine (used in the freeze/float/withdraw preview), 4.28 grievance register with escalation.
Documents are stored under `data/uploads` until Cloudflare R2 keys (`R2_*`) are set. Payments are recorded
manually (Razorpay later).

**Phase 4 (self-service & growth) — built.**
- 4.10 Student & parent portal at `/portal`: phone + password login, activated with a one-time code that staff
  issue from the student's "Portal & alumni" tab. Shows the next deadline with a countdown, each counselling
  track side by side, documents (with upload into the verification queue), fees, shortlist, and questions
  (which become helpdesk tickets). Portal logins use a separate token type and cannot call staff endpoints.
- 4.12 Marketing: campaigns, spend, and cost per lead / per confirmed admission by channel and campaign
  (associate commission counts as the cost of the referral channel).
- 4.13 Analytics (round-day numbers, funnel, revenue, outcomes by category and state) and a report builder
  with column selection, CSV download for Excel and print-to-PDF. Voice-agent metrics are on the AI voice agent page.
- 4.15 Branches: staff with a branch only see that branch's leads, students, documents queue, dues and
  tickets; admins and head-office staff see everything, with a branch filter and a branch comparison.
  The round desk and dashboard counters are not yet split by branch.
- 4.22 Associates & sub-agents: territory, agreement dates and terms, performance and commission totals.
- 4.23 Alumni directory: satisfaction surveys, testimonials (public use needs consent + admin approval),
  and past-student referral tracking on leads.

**Phase 5 (advanced & integrations) — built to work without outside providers.** No provider account is
connected yet, so each module does the useful part itself and leaves a slot for the provider. The admin page
"Connected services" (`/admin/integrations`) lists what is and is not connected.
- 4.18 Lead score and student drop-off risk from visible point rules (each score shows its reasons), and a
  "What to do next" list on the dashboard and at `/priorities`. Not a trained model.
- 4.26 Education loans: lender list, applications tracked against the date the money is needed by, urgent
  alert when approval is still pending a week before it. No lender API.
- 4.19 Counselling sessions with notes on the student record; video sessions get a Jitsi Meet link unless a
  Zoom/Meet link is pasted. No automatic recordings.
- 4.21 Call button (opens the dialer) with outcome logging on leads and students. No telephony provider.
- 4.20 Agreements and consent forms: issued from editable wording, accepted by the family in the portal
  (typed name, time, login) or recorded as signed on paper, stored with a fingerprint of the exact text.
  This is **not** Aadhaar e-Sign. The starter wording is a draft and needs a lawyer's review.
- 4.17 Website assistant at `/ask` (public, rate limited): answers only from the FAQ knowledge base by
  keyword match and turns visitors into leads. No AI model and no WhatsApp bot.
- 4.25 English / Tamil / Hindi for family alerts, the assistant and the FAQ. The Tamil and Hindi text was
  machine-written and needs a native speaker's review.
- 4.16 DigiLocker: not built (needs partner approval); documents carry a `source` column ready for it.

**Phase 6 (AI voice calling agent, spec 4.29 and section 18) — built in simulated mode.** Everything except the
live phone call: consent and opt-out per number, a do-not-disturb check, versioned call scripts that an admin
approves, campaigns (who to call, a preview of who cannot be called and why, calling hours in Indian time,
retries, a "Pause all" switch), a signed Tool API with 14 tools and server-side identity verification, call
webhooks, incoming-call lookup, handoff to a person (live transfer or a callback task, plus a grievance draft for
refund disputes), transcripts with the agent's look-ups, metrics and a cost estimate, and a test console. No
telephony or voice-AI provider is connected yet, so campaign calls are recorded as simulated and no phone rings;
the test console uses a rule-based stand-in for the AI. Connecting a provider is one adapter class plus settings:
see [docs/voice-agent.md](docs/voice-agent.md). The starter scripts are drafts and must be read and approved first.
Works on phones and laptops (tables turn into cards on small screens).

## Stack

| Part | Tech |
|------|------|
| `backend/` | Java 21, Spring Boot 4.1, Spring Security (JWT + rotating refresh cookie), JPA/Hibernate, Flyway, PostgreSQL |
| `frontend/` | Next.js 16 (App Router, client-rendered staff app), React 19, TypeScript, Tailwind CSS 4 |

The browser only talks to the Next.js origin; Next proxies `/api/*` to the backend (`BACKEND_URL`).

## Run locally

Prerequisites: JDK 21, Maven, Node 20+, PostgreSQL.

1. Create the database: `createdb -U postgres mbbs_crm`
2. Backend settings: copy `backend/.env.example` to `backend/.env` and fill it in
   (DB password, a random `JWT_SECRET`, first admin email/password and `ADMIN_PHONE`, `PORT`).
   There is **one login page for everyone** (`/login`): a mobile number and a one-time code. The number
   decides who you are. The single admin (`ADMIN_PHONE`) and staff open the CRM; students and parents open
   their portal. Staff IDs are created only by the admin (Admin → Staff users); only students can use
   "Register" on the login page. No WhatsApp/SMS provider is connected, so
   set `OTP_SHOW_ON_SCREEN=true` for local use: the code is then shown on the login page. **While that is on,
   anyone who knows a registered mobile number can log in as that person, so never enable it on a site others can reach.**
   Sessions end after 3 minutes without activity (`SESSION_IDLE_TIMEOUT`).
3. Start the backend (from `backend/`): `mvn spring-boot:run`
   Flyway creates the tables; the first super admin is created from `ADMIN_EMAIL` / `ADMIN_PASSWORD`.
4. Frontend settings: copy `frontend/.env.example` to `frontend/.env.local` and set `BACKEND_URL`
   to the backend's address.
5. Start the frontend (from `frontend/`): `npm install` then `npm run dev`, and open http://localhost:3000.

`.env` / `.env.local` are git-ignored. Never commit real passwords or secrets.

## Tests

- Backend: `mvn test` (unit tests plus API integration tests against H2 in PostgreSQL mode).
- Frontend: `npx tsc --noEmit` and `npm run lint`.

## Roles (Phase 1)

| Role | Can do |
|------|--------|
| Super Admin | Everything; manages staff; approves college-data changes |
| Counsellor | Own + unassigned leads; converts leads; own students |
| Telecaller | Own + unassigned leads; no student profiles |
| Data / Research | Proposes college, seat-matrix, fee and cutoff changes (need admin approval) |
| Documentation, Accountant, Loan Desk, Grievance Officer | Read student profiles (their own modules arrive in later phases) |
