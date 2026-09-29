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
Gupshup/Interakt or MSG91 is connected by adding a  implementation.

Next: Phase 3 (documents, fees, staff management, helpdesk, refund rules engine, grievance register) per spec
section 12. Works on phones and laptops (tables turn into cards on small screens).

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
   (DB password, a random `JWT_SECRET`, first admin email/password, `PORT`).
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
