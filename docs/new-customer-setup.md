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
2. Deploy the backend and the frontend (for example Render, Railway or a small VPS) with HTTPS.
3. Set the backend settings (see `backend/.env.example`), all as real environment variables:
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
   - `JWT_SECRET`: 32+ random characters, different for every customer
   - `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `ADMIN_NAME`, `ADMIN_PHONE`: the customer's admin
   - `COOKIE_SECURE=true`
   - `OTP_SHOW_ON_SCREEN=false`, and connect an SMS/WhatsApp provider for login codes before going live
     (until then, anyone who knows a staff number can sign in)
   - `VOICE_HMAC_SECRET`: 32+ random characters, different for every customer
4. Set `BACKEND_URL` on the frontend to the backend's address.

## 3. Brand it (no code needed)

Sign in as the admin and open **Company settings**: company name, the AI agent's name, brand colour, and what
contacts are called (Customer, Patient, Student, Member...).

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
