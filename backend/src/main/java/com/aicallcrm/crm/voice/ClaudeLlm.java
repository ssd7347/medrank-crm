package com.aicallcrm.crm.voice;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.models.messages.CacheControlEphemeral;
import com.anthropic.models.messages.ContentBlock;
import com.anthropic.models.messages.ContentBlockParam;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.MessageParam;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.TextBlockParam;
import com.anthropic.models.messages.Tool;
import com.anthropic.models.messages.ToolResultBlockParam;
import com.anthropic.models.messages.Usage;

import tools.jackson.databind.ObjectMapper;

/**
 * Claude as the brain, through Anthropic's official Java SDK, with prompt caching.
 *
 * <p>Every turn re-sends the same tools and script, so they are cached: an explicit breakpoint on the system
 * prompt (it renders after the tools, so it caches both) plus automatic caching of the growing conversation.
 * Cache reads cost a tenth of normal input. Each model has a minimum cacheable size (Claude Haiku 4.5: 4,096
 * tokens; Claude Sonnet 5.5 and Opus 5.5: 512), below which caching silently does nothing; the usage figures
 * logged at debug level show whether it is working.
 */
final class ClaudeLlm implements LlmClient {

    private static final Logger log = LoggerFactory.getLogger(ClaudeLlm.class);

    private final AiProperties props;
    private final ObjectMapper mapper;
    private final AnthropicClient client;

    ClaudeLlm(AiProperties props, ObjectMapper mapper) {
        this.props = props;
        this.mapper = mapper;
        this.client = AnthropicOkHttpClient.builder()
                .apiKey(props.anthropicApiKey())
                .baseUrl(props.anthropicUrl())
                .timeout(Duration.ofSeconds(props.timeoutSeconds()))
                // A caller is waiting on the line: one quick retry, then the brain hands over to a person.
                .maxRetries(1)
                .build();
    }

    @Override
    public String describe() {
        return "Claude (" + props.anthropicModel() + ")";
    }

    @Override
    @SuppressWarnings("unchecked")
    public Reply chat(String system, List<Message> messages, List<ToolSpec> tools) {
        MessageCreateParams.Builder b = MessageCreateParams.builder()
                .model(props.anthropicModel())
                .maxTokens(props.maxReplyTokens())
                // Breakpoint 1: tools + script, identical on every turn of the call.
                .systemOfTextBlockParams(List.of(TextBlockParam.builder().text(system)
                        .cacheControl(CacheControlEphemeral.builder().build()).build()))
                // Breakpoint 2 (automatic): the conversation so far, which each turn only appends to.
                .cacheControl(CacheControlEphemeral.builder().build());
        // A phone call needs quick, short answers. Claude Haiku 4.5 takes no effort setting.
        if (!props.anthropicModel().startsWith("claude-haiku-4-5")) {
            b.outputConfig(OutputConfig.builder().effort(OutputConfig.Effort.LOW).build());
        }
        // Same order every time, so the cached prefix stays byte-identical.
        tools.stream().sorted(java.util.Comparator.comparing(ToolSpec::name)).forEach(t -> b.addTool(tool(t)));
        addMessages(b, messages);

        com.anthropic.models.messages.Message res = client.messages().create(b.build());
        Usage u = res.usage();
        log.debug("Claude usage: input={} cache_write={} cache_read={} output={}", u.inputTokens(),
                u.cacheCreationInputTokens().orElse(0L), u.cacheReadInputTokens().orElse(0L), u.outputTokens());

        StringBuilder text = new StringBuilder();
        List<ToolCall> calls = new ArrayList<>();
        for (ContentBlock block : res.content()) {
            block.text().ifPresent(t -> text.append(t.text()));
            block.toolUse().ifPresent(tu -> calls.add(new ToolCall(tu.id(), tu.name(),
                    tu._input().convert(Map.class) instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of())));
        }
        // The reply goes back unchanged on the next turn (thinking blocks included), keeping the cache intact.
        return new Reply(text.toString().trim(), calls, res);
    }

    /** Claude wants the results of one turn's tool calls together, in a single user message. */
    private static void addMessages(MessageCreateParams.Builder b, List<Message> messages) {
        List<ContentBlockParam> results = new ArrayList<>();
        for (Message m : messages) {
            if ("tool".equals(m.role())) {
                results.add(ContentBlockParam.ofToolResult(ToolResultBlockParam.builder()
                        .toolUseId(m.toolCallId()).content(m.text()).build()));
                continue;
            }
            if (!results.isEmpty()) {
                b.addMessage(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(List.copyOf(results)).build());
                results.clear();
            }
            if ("assistant".equals(m.role())) {
                if (m.raw() instanceof com.anthropic.models.messages.Message reply) {
                    b.addMessage(reply);
                } else {
                    b.addMessage(MessageParam.builder().role(MessageParam.Role.ASSISTANT).content(m.text()).build());
                }
            } else {
                b.addMessage(MessageParam.builder().role(MessageParam.Role.USER).content(m.text()).build());
            }
        }
        if (!results.isEmpty()) {
            b.addMessage(MessageParam.builder().role(MessageParam.Role.USER).contentOfBlockParams(List.copyOf(results)).build());
        }
    }

    @SuppressWarnings("unchecked")
    private static Tool tool(ToolSpec t) {
        Map<String, Object> schema = t.inputSchema();
        Map<String, JsonValue> props = new LinkedHashMap<>();
        if (schema.get("properties") instanceof Map<?, ?> p) {
            ((Map<String, Object>) p).forEach((k, v) -> props.put(k, JsonValue.from(v)));
        }
        Tool.InputSchema.Builder input = Tool.InputSchema.builder()
                .properties(Tool.InputSchema.Properties.builder().putAllAdditionalProperties(props).build());
        if (schema.get("required") instanceof List<?> req && !req.isEmpty()) {
            input.required(req.stream().map(String::valueOf).toList());
        }
        return Tool.builder().name(t.name()).description(t.description()).inputSchema(input.build()).build();
    }
}
