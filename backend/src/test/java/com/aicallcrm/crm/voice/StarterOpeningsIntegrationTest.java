package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * The starter opening lines say it is an AI and that the call may be recorded (real calls are recorded since 17 Nov
 * 2026, and only when the opening says so), and do not tell whoever answers why we are calling before the identity
 * check. Approved scripts are never rewritten.
 */
@SpringBootTest
@ActiveProfiles("test")
class StarterOpeningsIntegrationTest {

    private static final String RECORDED = " This call may be recorded.";
    private static final String OUTBOUND = "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}."
            + RECORDED + " Am I speaking with {{first_name}}?";
    /** What V10 left (15 Nov 2026, while nothing was recorded). */
    private static final String V10_OUTBOUND = OUTBOUND.replace(RECORDED, "");
    private static final Pattern REASON = Pattern.compile("record|appointment|check-up|enquir|admission|property|"
            + "site visit|renewal|policy|fee|emi|loan|due|overdue|payment");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void theBuiltInStartersSayTheyAreAnAiThatTheCallMayBeRecordedAndNothingMore() {
        Map<String, String> expected = Map.of(
                "Payment reminder", OUTBOUND,
                "Appointment reminder", OUTBOUND,
                "Enquiry follow-up", OUTBOUND,
                "Feedback survey", "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}."
                        + RECORDED + " Do you have one minute to share feedback?",
                "Incoming calls", "Hello, you have reached {{company_name}}. I am {{agent_name}}, an AI assistant,"
                        + " and this call may be recorded. How can I help you today?");
        expected.forEach((name, opening) -> {
            assertThat(jdbc.queryForObject("select opening_line from voice_script where name = ? and version = 1",
                    String.class, name)).as(name).isEqualTo(opening);
            assertThat(RecordingService.announces(opening)).as(name).isTrue();
            assertThat(VoiceScriptService.openingWarnings(opening)).as(name).isEmpty();
        });
    }

    @Test
    void everyStarterPackOpeningSaysItMayBeRecordedAndIsOtherwiseNeutral() throws Exception {
        int seen = 0;
        for (String pack : List.of("clinic", "education", "finance", "insurance", "real_estate")) {
            try (InputStream in = new ClassPathResource("packs/" + pack + ".json").getInputStream()) {
                JsonNode root = mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                for (JsonNode script : root.get("scripts")) {
                    String opening = script.get("opening").asString();
                    assertThat(opening).as(pack).isEqualTo(OUTBOUND);
                    // Apart from the recording notice, nothing that says why we are calling.
                    assertThat(REASON.matcher(opening.replace(RECORDED, "").toLowerCase(Locale.ROOT)).find()).isFalse();
                    seen++;
                }
            }
        }
        assertThat(seen).isEqualTo(9);
    }

    /** The V10 migration itself, run again on two scripts with the old wording: only the draft changes. */
    @Test
    void theMigrationRewritesDraftsButNeverAnApprovedScript() throws Exception {
        String old = "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}. This call may be"
                + " recorded. Am I speaking with {{first_name}}? I am calling because your check-up is due.";
        insert("V10 approved", old, true);
        insert("V10 draft", old, false);
        run("V10__truthful_openings.sql", "V10 ");
        assertThat(opening("V10 approved")).isEqualTo(old);
        assertThat(opening("V10 draft")).isEqualTo(V10_OUTBOUND);
    }

    /** V12 puts the recording notice back into drafts that V10 had taken it out of; approved scripts stay as they are. */
    @Test
    void theRecordingMigrationAddsTheNoticeToDraftsOnly() throws Exception {
        String inbound = "Hello, you have reached {{company_name}}. I am {{agent_name}}, an AI assistant. How can I help"
                + " you today?";
        String survey = "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}. Do you have one"
                + " minute to share feedback?";
        String own = "Hi {{first_name}}, {{company_name}} here.";
        insert("V12 approved", V10_OUTBOUND, true);
        insert("V12 draft", V10_OUTBOUND, false);
        insert("V12 inbound", inbound, false);
        insert("V12 survey", survey, false);
        insert("V12 own words", own, false);
        insert("V12 already", OUTBOUND, false);
        run("V12__call_recordings.sql", "V12 ");
        assertThat(opening("V12 approved")).isEqualTo(V10_OUTBOUND);
        assertThat(opening("V12 draft")).isEqualTo(OUTBOUND);
        assertThat(opening("V12 inbound")).isEqualTo("Hello, you have reached {{company_name}}. I am {{agent_name}}, an AI"
                + " assistant, and this call may be recorded. How can I help you today?");
        assertThat(opening("V12 survey")).isEqualTo("Hello, this is {{agent_name}}, an AI assistant calling from"
                + " {{company_name}}." + RECORDED + " Do you have one minute to share feedback?");
        // A company's own wording is left for the admin (the scripts page flags it); nothing is said twice.
        assertThat(opening("V12 own words")).isEqualTo(own);
        assertThat(opening("V12 already")).isEqualTo(OUTBOUND);
    }

    private void insert(String name, String opening, boolean approved) {
        jdbc.update("insert into voice_script (name, kind, version, model, system_prompt, opening_line, approved_at,"
                + " active, created_at) values (?, 'OUTBOUND', 1, 'm', 'p', ?, " + (approved ? "current_timestamp" : "null")
                + ", false, current_timestamp)", name, opening);
    }

    private String opening(String name) {
        return jdbc.queryForObject("select opening_line from voice_script where name = ?", String.class, name);
    }

    /**
     * Runs a migration's statements again, without its comments, on this test's scripts only (the in-memory database
     * is shared with other tests); the column it adds is already there.
     */
    private void run(String migration, String names) throws Exception {
        String sql;
        try (InputStream in = new ClassPathResource("db/migration/" + migration).getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        for (String statement : sql.replaceAll("(?m)^--.*$", "").split(";")) {
            if (!statement.isBlank() && !statement.strip().startsWith("ALTER TABLE")) {
                String only = statement.strip().replace("WHERE approved_at IS NULL",
                        "WHERE name LIKE '" + names + "%' AND approved_at IS NULL");
                assertThat(only).contains("WHERE name LIKE");
                jdbc.execute(only);
            }
        }
    }
}
