# Setting up the CRM for a new customer

Each customer gets their own copy: their own server (or hosting account), their own database and their own
settings. Nothing is shared between customers, so one customer can never see another's data.

## 1. Before you install

Agree with the customer, in writing:

- What they want the AI to call about (payment reminders, appointments, follow-ups, surveys...).
- Which of their people need logins, and who is the one admin.
- That the people being called **agreed to be called**, and how (a form, a contract clause). In India this also
  means DLT registration with the telecom operator and respecting do-not-disturb rules; abroad, the local rules
  (TCPA in the US, GDPR in Europe).
- Who pays the per-minute call charges of the voice-AI platform and the telephony provider.

## 2. Install

1. Create a PostgreSQL database for this customer (a managed one such as Neon, Supabase or AWS RDS is easiest).
2. Deploy the backend and the frontend (for example Render, Railway or a small VPS) with HTTPS: see "Building and
   starting on a server" below.
3. Set the backend settings (see `backend/.env.example`), all as real environment variables:
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
   - `JWT_SECRET`: 32+ random characters, different for every customer
   - `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `ADMIN_NAME`, `ADMIN_PHONE`: the customer's admin
   - `COOKIE_SECURE=true`
   - `OTP_SHOW_ON_SCREEN=false`, and sign-in codes by SMS (below) before going live (until then, anyone who
     knows a staff number can sign in)
   - `VOICE_HMAC_SECRET`: 32+ random characters, different for every customer
   - `FEATURES`: the modules this customer has paid for (`ALL`, or for example `IMPORT,EXPORT` for a plan
     without AI calls). Switched-off modules disappear from the menu and their API refuses requests, so the
     customer cannot turn them on themselves.
4. Set `BACKEND_URL` on the frontend to the backend's address **before building it** (see below).

### Building and starting on a server

These are the steps rehearsed on 18 Nov 2026: the built jar and the production website, with the live-site settings,
on an empty PostgreSQL 17 database. The speech engine and Plivo were not part of that rehearsal.

**CRM server** (Java 21):

```bash
cd backend
./mvnw -DskipTests package
java -jar target/ai-call-crm-backend-0.0.1-SNAPSHOT.jar
```

- **Settings are real environment variables.** The CRM also reads a `.env` file from the folder it starts in, so
  don't start it from a folder holding a developer's `.env`.
- **`DB_URL` is required.** Without it the CRM stops at start instead of guessing a database.
- **On an empty database, the first start creates all the tables** (Flyway, 12 steps) and the admin from
  `ADMIN_EMAIL` / `ADMIN_PHONE`.
- **`PORT` sets its port** (default 8080). It should not be reachable from the internet; the website passes `/api`
  on to it.

**Website** (Node.js):

```bash
cd frontend
npm ci
BACKEND_URL=http://localhost:8080 npm run build
npm run start -- --port 3000
```

- **`BACKEND_URL` is fixed into the website when it is built.** Setting it only when starting has no effect. If
  the CRM server's address changes, build again.
- **Only the website's port is put on the internet, behind HTTPS** (the hosting's, or a reverse proxy such as Caddy
  or nginx). It passes every `/api/...` request to the CRM server.
- **The proxy's access log can hold customers' names and numbers.** A contact search puts what was typed in the
  address (`?q=...`).
  - Log addresses without their query (nginx: `$uri` instead of `$request` in the `log_format`), or keep the log
    short (compliance checklist 2.1).
  - The CRM's own log keeps them out.
- **The sign-in cookie is `Secure`**, so on a real address the site works only over HTTPS. On `localhost` browsers
  allow it without HTTPS.
- **The website tells browsers to use HTTPS only** (`Strict-Transport-Security`, one year), and to never show it
  inside another site.

**Backups**: before the first real customer data goes in, schedule `ops/backup.py`.
- **Nightly `backup`**: the database and the recordings folder, to another disk.
- **Weekly `check`**: proves the newest backup restores.
- **Then run both once by hand** and read their output.

`docs/backups.md` has the scheduling lines for Linux and Windows. It needs Python 3.10+ and the PostgreSQL client
tools.

### Sign-in codes by SMS (MSG91)

Staff sign in with their mobile number and a 6-digit code, sent by SMS through the customer's own MSG91 account.
Plivo is not used for this: Plivo's documentation says it no longer sends domestic India SMS, and its India route
needs a large monthly commitment.

1. The customer opens an MSG91 account in the company's name and registers on DLT: the company (principal entity),
   a sender ID (header) and a sign-in template. In the template, the code is a variable. For example: "Your sign-in
   code for <Brand> CRM is {#var#}. It works once, for 5 minutes. Do not share it."
2. In MSG91 under SMS → Templates, add that template with its DLT template ID and the sender ID, writing the code's
   variable as `##OTP##`. Note MSG91's template id. Use the SMS section, not the OTP section: the CRM sends through
   MSG91's Flow API, which uses SMS-section templates.
3. On the backend, set `OTP_SMS_PROVIDER=msg91`, `MSG91_AUTH_KEY` (MSG91 → API key, only on the server) and
   `MSG91_OTP_TEMPLATE_ID`. If the variable is not `OTP`, also set `MSG91_OTP_VARIABLE`. A mistyped provider or
   variable name stops the CRM from starting.
4. Restart, then sign in with the admin's number. The SMS should arrive within a minute. The CRM log says "accepted
   by MSG91" or why not. MSG91's SMS log says whether the operator delivered it. "Connected services" shows SMS for
   login codes as connected.
5. Only then set `OTP_SHOW_ON_SCREEN=false` (if it was true) and put the site online.

The CRM has been tested against a stand-in for MSG91, not a real MSG91 account, so step 4 is the first real test.
The login page's reply is the same, and just as quick, whether or not the number has an account. So a code that
does not arrive shows up only in the logs.

## 3. Brand it (no code needed)

Sign in as the admin:

1. **Industry starter packs**: load the pack closest to the customer's business (clinic, loans and EMI,
   real estate, education, insurance). It adds stages, custom fields, call scripts and knowledge-base answers,
   and sets what contacts are called. It never deletes or overwrites anything, so it is safe to load later too.
   Its answers arrive switched off and its scripts as drafts, because they contain [placeholders] to fill in.
2. **Company settings**: company name, logo, the AI agent's name, brand colour, what contacts are called
   (Customer, Patient, Student, Member...) and the login-page headline and text.
3. **Stages**: rename, recolour and reorder the steps contacts move through, and add the customer's own.
   The built-in ones (New, Contacted, Interested, Not interested, Do not call) are used by the AI agent and
   cannot be deleted, only renamed.

## 4. Load their data

1. **Import from Excel** with one of their real files. Check the guessed columns before importing.
2. **Custom fields**: for each new field decide what the AI may say about it:
   - *Never read out* for anything only used to check identity (customer ID, date of birth).
   - *Only after the caller is verified* for private details (amount due, appointment time).
   - *To anyone who answers* only for harmless details.
   Tick "Ask to confirm" on one or two fields the caller would know, such as a customer ID.
3. **AI knowledge base**: replace the starter answers with theirs (hours, prices, policies).

## 5. The AI agent

1. **Scripts**: adapt the starter scripts to the customer's wording and approve them.
2. **Test console**: play through real situations (wrong person, busy, complaint, "stop calling me") with one
   of their contacts. Test calls never change anything for the contact.
3. Connect the voice-AI platform and telephony provider (docs/voice-agent.md), then run a small campaign and read
   every transcript before scaling up.

## 6. Changing the code for one customer

Keep one main copy of the code and make customer-specific changes on a separate branch (or a fork) per customer,
so improvements to the main copy can still be merged in. Most customers should need no code changes at all:
names, colours, fields, scripts and answers are all settings.
