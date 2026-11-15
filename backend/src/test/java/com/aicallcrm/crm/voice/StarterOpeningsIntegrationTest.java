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
 * The starter opening lines say it is an AI, do not claim the call may be recorded (nothing is recorded), and do not
 * tell whoever answers why we are calling before the identity check. Approved scripts are never rewritten.
 */
@SpringBootTest
@ActiveProfiles("test")
class StarterOpeningsIntegrationTest {

    private static final String OUTBOUND = "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}."
            + " Am I speaking with {{first_name}}?";
    private static final Pattern REASON = Pattern.compile("record|appointment|check-up|enquir|admission|property|"
            + "site visit|renewal|policy|fee|emi|loan|due|overdue|payment");

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    ObjectMapper mapper;

    @Test
    void theBuiltInStartersSayTheyAreAnAiAndNothingMore() {
        Map<String, String> expected = Map.of(
                "Payment reminder", OUTBOUND,
                "Appointment reminder", OUTBOUND,
                "Enquiry follow-up", OUTBOUND,
                "Feedback survey", "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}."
                        + " Do you have one minute to share feedback?",
                "Incoming calls", "Hello, you have reached {{company_name}}. I am {{agent_name}}, an AI assistant."
                        + " How can I help you today?");
        expected.forEach((name, opening) -> assertThat(jdbc.queryForObject(
                "select opening_line from voice_script where name = ? and version = 1", String.class, name))
                .as(name).isEqualTo(opening));
    }

    @Test
    void everyStarterPackOpeningIsNeutral() throws Exception {
        int seen = 0;
        for (String pack : List.of("clinic", "education", "finance", "insurance", "real_estate")) {
            try (InputStream in = new ClassPathResource("packs/" + pack + ".json").getInputStream()) {
                JsonNode root = mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                for (JsonNode script : root.get("scripts")) {
                    String opening = script.get("opening").asString();
                    assertThat(opening).as(pack).isEqualTo(OUTBOUND);
                    assertThat(REASON.matcher(opening.toLowerCase(Locale.ROOT)).find()).isFalse();
                    seen++;
                }
            }
        }
        assertThat(seen).isEqualTo(9);
    }

    /** The migration itself, run again on two scripts with the old wording: only the draft changes. */
    @Test
    void theMigrationRewritesDraftsButNeverAnApprovedScript() throws Exception {
        String old = "Hello, this is {{agent_name}}, an AI assistant calling from {{company_name}}. This call may be"
                + " recorded. Am I speaking with {{first_name}}? I am calling because your check-up is due.";
        jdbc.update("insert into voice_script (name, kind, version, model, system_prompt, opening_line, approved_at,"
                + " active, created_at) values ('V10 approved', 'OUTBOUND', 1, 'm', 'p', ?, current_timestamp, false,"
                + " current_timestamp)", old);
        jdbc.update("insert into voice_script (name, kind, version, model, system_prompt, opening_line, active,"
                + " created_at) values ('V10 draft', 'OUTBOUND', 1, 'm', 'p', ?, false, current_timestamp)", old);
        String sql;
        try (InputStream in = new ClassPathResource("db/migration/V10__truthful_openings.sql").getInputStream()) {
            sql = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        jdbc.execute(sql.replaceAll("(?m)^--.*$", "").trim().replaceAll(";$", ""));
        assertThat(jdbc.queryForObject("select opening_line from voice_script where name = 'V10 approved'", String.class))
                .isEqualTo(old);
        assertThat(jdbc.queryForObject("select opening_line from voice_script where name = 'V10 draft'", String.class))
                .isEqualTo(OUTBOUND);
    }
}
