package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
