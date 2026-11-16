package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/**
 * The training kit in {@code training/} teaches our own model to be the agent, so it must see exactly the rules
 * and tools the live agent gets. This keeps {@code training/spec/agent-spec.json} identical to the code: if a tool
 * or the voice rules change, this test fails until the file is rewritten with
 * {@code mvn test -Dtest=TrainingSpecTest -Dtraining.spec.write=true}.
 */
@SpringBootTest
@ActiveProfiles("test")
class TrainingSpecTest {

    private static final Path SPEC = Path.of("..", "training", "spec", "agent-spec.json");
    private static final JsonMapper JSON = JsonMapper.builder()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
            .build();

    @Autowired
    VoiceTools tools;

    /** The default desk hours (app.voice.desk-open / desk-close), fixed here so a local setting never changes the file. */
    private static final LocalTime DESK_OPEN = LocalTime.of(9, 30);
    private static final LocalTime DESK_CLOSE = LocalTime.of(18, 30);

    /** What callers ask for: the kit's own (scenarios.WHEN_CALL), and CallbackTimeTest's harder cases. */
    private static final List<String> ASKED = List.of("this evening", "tomorrow morning", "after 5 pm", "Saturday morning",
            "in two hours", "tomorrow after lunch", "Please call me back at 4 pm.", "4 pm", "4pm", "4 p.m.", "4 P.M.", "16:00",
            "at 4", "4 o'clock", "4 o’clock", "around 4", "Good morning, please call me at 4",
            "My ref is 4471 and I owe 2,500, call me at 4", "call me at 4 in the evening", "4.30 pm", "at 4:30",
            "tomorrow at 10:30 am", "noon tomorrow", "day after tomorrow at 3 pm", "Wednesday at 11", "next Monday at 11 am",
            "in 2 hours", "in an hour and a half", "after half an hour", "in 40 minutes", "call me at 10", "today at 10",
            "in the evening", "after 5", "between 4 and 5 pm", "4-5 pm", "4 to 5 pm", "tomorrow between 11 and 1 pm",
            "from 10 to 12", "tomorrow", "this morning", "this afternoon", "before 3 pm", "by 5", "until 6",
            "within 2 hours", "in the morning", "at 8 pm", "tonight", "Sunday at 4", "after 7 pm", "before 9",
            "in 9 hours", "", "soon", "later", "whenever", "next week", "on the 20th", "not tomorrow, call me on Wednesday",
            "I'm busy at 4", "don't call at 4, call at 5", "dont call me tomorrow", "tomorrow or Wednesday", "at 4 or at 5",
            "in the morning or the evening", "in 10 minutes", "today", "between 5 and 3 pm", "at 25", "13 pm", "at 4:75",
            "I owe 12.50", "sometime in the next 2 days", "Monday evening", "next friday afternoon", "at 12", "at 9",
            "tomorrow night", "after 6:15 pm", "around 5.45", "in 1.5 hours", "day after tomorow");
    /** When they ask (India time): a Monday morning, near closing, a Saturday evening, and the kit's own dates. */
    private static final List<LocalDateTime> ASKED_AT = List.of(LocalDateTime.of(2026, 11, 16, 11, 0),
            LocalDateTime.of(2026, 11, 16, 11, 50), LocalDateTime.of(2026, 11, 16, 17, 40),
            LocalDateTime.of(2026, 11, 21, 17, 0), LocalDateTime.of(2026, 10, 14, 11, 0));

    /**
     * The promise the CRM makes (HandoffService.callback: CallbackTime, with two hours of desk time as the usual
     * time) for each request at each moment. The kit's port of it (callkit.callback_promise) must give the same.
     */
    private static List<Map<String, Object>> callbackTimeExamples() {
        DateTimeFormatter local = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm");
        List<Map<String, Object>> out = new ArrayList<>();
        for (LocalDateTime at : ASKED_AT) {
            Instant now = at.atZone(ContactFacts.IST).toInstant();
            Instant usual = HandoffService.plusDeskTime(now, Duration.ofHours(2), DESK_OPEN, DESK_CLOSE);
            for (String asked : ASKED) {
                CallbackTime.Promise p = CallbackTime.promise(asked, now, DESK_OPEN, DESK_CLOSE, usual);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("asked", asked);
                row.put("now", local.format(at));
                row.put("due", local.format(p.due().atZone(ContactFacts.IST)));
                row.put("from", p.from() == null ? null : local.format(p.from().atZone(ContactFacts.IST)));
                row.put("message", p.message());
                row.put("slot", p.slot());
                out.add(row);
            }
        }
        return out;
    }

    @Test
    void trainingSpecMatchesTheLiveAgent() throws Exception {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("voice_rules", LlmBrain.VOICE_RULES);
        spec.put("max_tool_rounds", LlmBrain.MAX_ROUNDS);
        spec.put("closing_tools", LlmBrain.CLOSING_TOOLS.stream().sorted().toList());
        // The same wire format the local brain sends (LlmClients.OpenAiCompatible), in the same order.
        List<Map<String, Object>> wire = LlmBrain.offered(tools).stream().map(t -> Map.<String, Object>of("type", "function",
                "function", Map.of("name", t.name(), "description", t.description(), "parameters", t.inputSchema()))).toList();
        spec.put("tools", wire);
        spec.put("outcomes", List.of(Voice.Outcome.values()).stream().map(Enum::name).toList());
        spec.put("handoff_reasons", List.of(Voice.HandoffReason.values()).stream().map(Enum::name).toList());
        // CallerSignals: the kit judges and simulates calls with the very same words and aliases.
        spec.put("handoff_reason_aliases", CallerSignals.REASON_ALIASES);
        spec.put("distress_words", CallerSignals.DISTRESS);
        spec.put("dispute_words", CallerSignals.DISPUTE);
        spec.put("reason_words", FactGuard.REASON_WORDS);
        spec.put("reason_fallback", FactGuard.REASON_FALLBACK);
        spec.put("reason_fallback_ai", FactGuard.REASON_FALLBACK_AI);
        spec.put("ai_question_words", FactGuard.AI_QUESTION);
        spec.put("opt_out_words", CallerSignals.OPT_OUT);
        spec.put("callback_words", CallerSignals.CALLBACK);
        spec.put("no_callback_words", CallerSignals.NO_CALLBACK);
        spec.put("callback_withdrawn_words", CallerSignals.CALLBACK_WITHDRAWN);
        spec.put("callback_withdrawn_not_after", CallerSignals.WITHDRAWN_NOT_AFTER);
        spec.put("callback_withdrawn_not_before", CallerSignals.WITHDRAWN_NOT_BEFORE);
        spec.put("callback_asked_arg", CallerSignals.CALLBACK_ASKED);
        spec.put("max_name_asks", VoiceTools.MAX_NAME_ASKS);
        spec.put("callback_desk_hours", List.of(DESK_OPEN.toString(), DESK_CLOSE.toString()));
        spec.put("callback_time_examples", callbackTimeExamples());

        String expected = JSON.writeValueAsString(spec) + "\n";
        if (Boolean.getBoolean("training.spec.write")) {
            Files.createDirectories(SPEC.getParent());
            Files.writeString(SPEC, expected, StandardCharsets.UTF_8);
        }
        assertThat(Files.exists(SPEC)).as("training/spec/agent-spec.json exists").isTrue();
        JsonNode onDisk = JSON.readTree(Files.readString(SPEC, StandardCharsets.UTF_8));
        assertThat(onDisk).as("training/spec/agent-spec.json is out of date: rerun this test with "
                + "-Dtraining.spec.write=true").isEqualTo(JSON.readTree(expected));
    }
}
