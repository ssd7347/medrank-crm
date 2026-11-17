-- Call recordings (decided 17 Nov 2026). Every real AI call whose opening line says it may be recorded is recorded;
-- only the admin and staff given this permission can play a recording.
ALTER TABLE app_user ADD COLUMN can_hear_recordings BOOLEAN NOT NULL DEFAULT FALSE;

-- The starter opening lines say again that the call may be recorded (V10 had taken it out while nothing was
-- recorded). Draft (never approved) scripts only: an approved script changes only when the admin saves and approves a
-- new version, and until then its calls are not recorded (the scripts page says so).
UPDATE voice_script SET opening_line =
    REPLACE(REPLACE(REPLACE(opening_line,
        'an AI assistant calling from {{company_name}}. Am I speaking with',
        'an AI assistant calling from {{company_name}}. This call may be recorded. Am I speaking with'),
        'an AI assistant calling from {{company_name}}. Do you have one minute',
        'an AI assistant calling from {{company_name}}. This call may be recorded. Do you have one minute'),
        'I am {{agent_name}}, an AI assistant. How can I help you today?',
        'I am {{agent_name}}, an AI assistant, and this call may be recorded. How can I help you today?')
WHERE approved_at IS NULL AND LOWER(opening_line) NOT LIKE '%record%';
