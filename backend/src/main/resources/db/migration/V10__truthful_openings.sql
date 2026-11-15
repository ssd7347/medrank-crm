-- The starter opening lines said "This call may be recorded", but calls are not recorded; and some named the reason
-- for the call ("your check-up is due") to whoever answers, before the identity check. Draft (never approved) scripts
-- get the corrected wording. An approved script is left alone: it changes only when the admin saves and approves a
-- new version.
UPDATE voice_script SET opening_line =
    REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(REPLACE(opening_line,
        ' This call may be recorded.', ''),
        ', and this call may be recorded.', '.'),
        ' I am calling about your upcoming appointment.', ''),
        ' I am calling because your check-up is due.', ''),
        ' You recently enquired about admission with us.', ''),
        ' You recently enquired about a property with us.', ''),
        ' You recently enquired with us.', ''),
        ' I am calling about your policy renewal.', ''),
        ' I am calling about your site visit.', '')
WHERE approved_at IS NULL;
