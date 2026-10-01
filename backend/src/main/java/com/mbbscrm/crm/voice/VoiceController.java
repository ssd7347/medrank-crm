package com.mbbscrm.crm.voice;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.common.PageResponse;
import com.mbbscrm.crm.voice.CampaignService.CampaignDetail;
import com.mbbscrm.crm.voice.CampaignService.CampaignRequest;
import com.mbbscrm.crm.voice.CampaignService.CampaignView;
import com.mbbscrm.crm.voice.ConsentService.ConsentRequest;
import com.mbbscrm.crm.voice.ConsentService.PhoneConsent;
import com.mbbscrm.crm.voice.TestCallService.SayRequest;
import com.mbbscrm.crm.voice.TestCallService.StartRequest;
import com.mbbscrm.crm.voice.TestCallService.TestCall;
import com.mbbscrm.crm.voice.Voice.CallStatus;
import com.mbbscrm.crm.voice.Voice.Outcome;
import com.mbbscrm.crm.voice.Voice.Purpose;
import com.mbbscrm.crm.voice.VoiceQueryService.CallDetail;
import com.mbbscrm.crm.voice.VoiceQueryService.CallRow;
import com.mbbscrm.crm.voice.VoiceQueryService.CallbackView;
import com.mbbscrm.crm.voice.VoiceQueryService.DoneRequest;
import com.mbbscrm.crm.voice.VoiceQueryService.FlagRequest;
import com.mbbscrm.crm.voice.VoiceQueryService.Metrics;
import com.mbbscrm.crm.voice.VoiceQueryService.Overview;
import com.mbbscrm.crm.voice.VoiceScriptService.ScriptRequest;
import com.mbbscrm.crm.voice.VoiceScriptService.ScriptView;

import jakarta.validation.Valid;

/** Staff screens of the AI voice agent (spec 4.29): calls, callbacks, consent, campaigns, scripts, testing. */
@RestController
@RequestMapping("/api")
public class VoiceController {

    public record PauseRequest(boolean paused) {
    }

    public record AssignRequest(Long userId) {
    }

    public record DispatchResult(int placed) {
    }

    /** Everything needed to set the agent up on a voice platform: where to call us and what each tool takes. */
    public record ProviderSetup(String platform, boolean live, boolean signingConfigured, String signatureHeader,
                                String timestampHeader, String signatureRule, String toolPath, String inboundPath,
                                List<String> webhookPaths, List<Map<String, Object>> tools) {
    }

    private final VoiceQueryService queries;
    private final ConsentService consent;
    private final CampaignService campaigns;
    private final CampaignDispatcher dispatcher;
    private final VoiceScriptService scripts;
    private final TestCallService testCalls;
    private final VoiceTools tools;
    private final VoicePlatforms platforms;
    private final VoiceProperties props;

    public VoiceController(VoiceQueryService queries, ConsentService consent, CampaignService campaigns,
                           CampaignDispatcher dispatcher, VoiceScriptService scripts, TestCallService testCalls,
                           VoiceTools tools, VoicePlatforms platforms, VoiceProperties props) {
        this.queries = queries;
        this.consent = consent;
        this.campaigns = campaigns;
        this.dispatcher = dispatcher;
        this.scripts = scripts;
        this.testCalls = testCalls;
        this.tools = tools;
        this.platforms = platforms;
        this.props = props;
    }

    // ------------------------------------------------------------------ overview

    @GetMapping("/voice/overview")
    public Overview overview() {
        return queries.overview();
    }

    @PostMapping("/voice/pause-all")
    public Overview pauseAll(@RequestBody PauseRequest req) {
        return queries.setPaused(req.paused());
    }

    @GetMapping("/voice/metrics")
    public Metrics metrics(@RequestParam(defaultValue = "30") int days) {
        return queries.metrics(days);
    }

    @GetMapping("/voice/provider-setup")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public ProviderSetup providerSetup() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (VoiceTools.Tool t : tools.all()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", t.name());
            m.put("description", t.description());
            m.put("min_verification_level", t.minLevel());
            m.put("input_schema", t.inputSchema());
            list.add(m);
        }
        return new ProviderSetup(platforms.current().name(), platforms.live(), props.signingConfigured(),
                VoiceSignatures.SIGNATURE_HEADER, VoiceSignatures.TIMESTAMP_HEADER,
                "sha256= + hex(HMAC-SHA256(secret, timestamp + \".\" + raw body))",
                "/api/voice/hooks/tools/{tool}", "/api/voice/hooks/inbound/lookup",
                List.of("/api/voice/hooks/webhooks/call-started", "/api/voice/hooks/webhooks/transcript",
                        "/api/voice/hooks/webhooks/call-ended"), list);
    }

    // ------------------------------------------------------------------ calls

    @GetMapping("/voice/calls")
    public PageResponse<CallRow> calls(@RequestParam(required = false) CallStatus status,
                                       @RequestParam(required = false) Outcome outcome,
                                       @RequestParam(required = false) Purpose purpose,
                                       @RequestParam(required = false) Long campaignId,
                                       @RequestParam(defaultValue = "false") boolean tests,
                                       @RequestParam(defaultValue = "false") boolean flagged,
                                       @RequestParam(defaultValue = "0") int page,
                                       @RequestParam(defaultValue = "25") int size) {
        return queries.search(status, outcome, purpose, campaignId, tests, flagged, page, size);
    }

    @GetMapping("/voice/calls/{id}")
    public CallDetail call(@PathVariable UUID id) {
        return queries.detail(id);
    }

    @PostMapping("/voice/calls/{id}/flag")
    public CallDetail flag(@PathVariable UUID id, @Valid @RequestBody FlagRequest req) {
        return queries.flag(id, req);
    }

    @GetMapping("/students/{studentId}/voice-calls")
    public List<CallRow> callsForStudent(@PathVariable Long studentId) {
        return queries.forStudent(studentId);
    }

    @GetMapping("/leads/{leadId}/voice-calls")
    public List<CallRow> callsForLead(@PathVariable Long leadId) {
        return queries.forLead(leadId);
    }

    // ------------------------------------------------------------------ consent

    @GetMapping("/students/{studentId}/voice-consent")
    public List<PhoneConsent> consentForStudent(@PathVariable Long studentId) {
        return consent.forStudent(studentId);
    }

    @PostMapping("/students/{studentId}/voice-consent")
    public List<PhoneConsent> recordForStudent(@PathVariable Long studentId, @Valid @RequestBody ConsentRequest req) {
        return consent.recordForStudent(studentId, req);
    }

    @GetMapping("/leads/{leadId}/voice-consent")
    public List<PhoneConsent> consentForLead(@PathVariable Long leadId) {
        return consent.forLead(leadId);
    }

    @PostMapping("/leads/{leadId}/voice-consent")
    public List<PhoneConsent> recordForLead(@PathVariable Long leadId, @Valid @RequestBody ConsentRequest req) {
        return consent.recordForLead(leadId, req);
    }

    // ------------------------------------------------------------------ callbacks

    @GetMapping("/voice/callbacks")
    public List<CallbackView> callbacks(@RequestParam(defaultValue = "false") boolean done) {
        return queries.callbackQueue(done);
    }

    @PostMapping("/voice/callbacks/{id}/assign")
    public CallbackView assign(@PathVariable Long id, @RequestBody(required = false) AssignRequest req) {
        return queries.assign(id, req == null ? null : req.userId());
    }

    @PostMapping("/voice/callbacks/{id}/done")
    public CallbackView done(@PathVariable Long id, @Valid @RequestBody(required = false) DoneRequest req) {
        return queries.complete(id, req);
    }

    // ------------------------------------------------------------------ scripts

    @GetMapping("/voice/scripts")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public List<ScriptView> scripts() {
        return scripts.all();
    }

    @PostMapping("/voice/scripts")
    @ResponseStatus(HttpStatus.CREATED)
    public ScriptView createScript(@Valid @RequestBody ScriptRequest req) {
        return scripts.create(req);
    }

    @PostMapping("/voice/scripts/{id}/approve")
    public ScriptView approveScript(@PathVariable Long id) {
        return scripts.approve(id);
    }

    // ------------------------------------------------------------------ campaigns

    @GetMapping("/voice/campaigns")
    public List<CampaignView> campaigns() {
        return campaigns.list();
    }

    @PostMapping("/voice/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignDetail createCampaign(@Valid @RequestBody CampaignRequest req) {
        return campaigns.create(req);
    }

    @GetMapping("/voice/campaigns/{id}")
    public CampaignDetail campaign(@PathVariable Long id) {
        return campaigns.get(id);
    }

    @PutMapping("/voice/campaigns/{id}")
    public CampaignDetail updateCampaign(@PathVariable Long id, @Valid @RequestBody CampaignRequest req) {
        return campaigns.update(id, req);
    }

    @PostMapping("/voice/campaigns/{id}/audience")
    public CampaignDetail refreshAudience(@PathVariable Long id) {
        return campaigns.refreshAudience(id);
    }

    @PostMapping("/voice/campaigns/{id}/start")
    public CampaignDetail start(@PathVariable Long id) {
        return campaigns.start(id);
    }

    @PostMapping("/voice/campaigns/{id}/pause")
    public CampaignDetail pause(@PathVariable Long id) {
        return campaigns.pause(id);
    }

    @PostMapping("/voice/campaigns/{id}/finish")
    public CampaignDetail finish(@PathVariable Long id) {
        return campaigns.finish(id);
    }

    /** Runs the dispatcher now instead of waiting up to 30 seconds for its next pass. */
    @PostMapping("/voice/dispatch-now")
    @PreAuthorize("hasRole('SUPER_ADMIN')")
    public DispatchResult dispatchNow() {
        return new DispatchResult(dispatcher.dispatch());
    }

    // ------------------------------------------------------------------ test console

    @PostMapping("/voice/test-calls")
    @ResponseStatus(HttpStatus.CREATED)
    public TestCall startTest(@Valid @RequestBody StartRequest req) {
        return testCalls.start(req);
    }

    @GetMapping("/voice/test-calls/{id}")
    public TestCall testCall(@PathVariable UUID id) {
        return testCalls.get(id);
    }

    @PostMapping("/voice/test-calls/{id}/say")
    public TestCall say(@PathVariable UUID id, @Valid @RequestBody SayRequest req) {
        return testCalls.say(id, req);
    }

    @PostMapping("/voice/test-calls/{id}/end")
    public TestCall endTest(@PathVariable UUID id) {
        return testCalls.end(id);
    }
}
