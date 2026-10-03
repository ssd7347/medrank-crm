package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

/** "Call everyone in this file now": an upload starts its own campaign, and every calling rule still applies. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ImportCallsIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(71000);
    private static final String MAPPING = "{\"Name\":\"name\",\"Mobile\":\"phone\",\"Amount due\":\"new:AMOUNT\"}";

    @Autowired
    MockMvc mvc;

    @Autowired
    CampaignDispatcher dispatcher;

    @Test
    void anUploadWithConsentIsCalledStraightAway() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String script = ownScript(admin);
        String p1 = phone(), p2 = phone();

        String result = upload(admin, csv(p1, p2), true, script).andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.calls.people").value(2))
                .andReturn().getResponse().getContentAsString();
        int campaignId = JsonPath.read(result, "$.calls.campaignId");
        int now = JsonPath.read(result, "$.calls.callingNow");
        int waiting = JsonPath.read(result, "$.calls.waiting");
        int notAllowed = JsonPath.read(result, "$.calls.notAllowed");
        assertThat(now + waiting + notAllowed).isEqualTo(2);
        assertThat(notAllowed).isZero();

        // An ordinary running campaign, limited to this upload, that the dialler then works through.
        mvc.perform(auth(get("/api/voice/campaigns/" + campaignId), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.campaign.status").value("RUNNING"))
                .andExpect(jsonPath("$.campaign.scriptName").value(script))
                .andExpect(jsonPath("$.campaign.importBatchId").value((int) (long) JsonPath.<Integer>read(result, "$.batchId")))
                .andExpect(jsonPath("$.targets.length()").value(2));
        if (now > 0) {
            assertThat(dispatcher.dispatch()).isGreaterThanOrEqualTo(1);
        }
    }

    @Test
    void withoutConsentNobodyIsCalled() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String script = ownScript(admin);
        upload(admin, csv(phone(), phone()), false, script).andExpect(status().isOk())
                .andExpect(jsonPath("$.calls.people").value(2))
                .andExpect(jsonPath("$.calls.callingNow").value(0))
                .andExpect(jsonPath("$.calls.notAllowed").value(2));
    }

    @Test
    void nothingIsImportedWhenCallsCannotStart() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String p = phone();
        // A draft script (from a starter pack, never approved) is refused before a single row is read.
        mvc.perform(auth(post("/api/settings/packs/insurance/apply"), admin)).andExpect(status().isOk());
        upload(admin, csv(p, phone()), true, "Policy renewal reminder").andExpect(status().isBadRequest());
        upload(admin, csv(p, phone()), true, "No such script").andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/contacts").param("q", p), admin)).andExpect(jsonPath("$.totalItems").value(0));

        // Only the admin starts calls; a manager can still import without them.
        String managerEmail = "manager" + SEQ.incrementAndGet() + "@importcalls.test";
        mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Import Manager","email":"%s","role":"MANAGER","password":"StaffPass123!"}""".formatted(managerEmail)))
                .andExpect(status().isCreated());
        String manager = login(managerEmail, "StaffPass123!");
        String script = ownScript(admin);
        upload(manager, csv(p, phone()), true, script).andExpect(status().isForbidden());
        upload(manager, csv(p, phone()), true, null).andExpect(status().isOk()).andExpect(jsonPath("$.calls").doesNotExist());
    }

    // ------------------------------------------------------------------ helpers

    private ResultActions upload(String token, byte[] csv, boolean consent, String script) throws Exception {
        String options = "{\"mapping\":" + MAPPING + ",\"updateExisting\":false,\"aiCallConsent\":" + consent
                + ",\"recordingConsent\":false,\"consentEvidence\":" + (consent ? "\"Signed form\"" : "null")
                + ",\"callScriptName\":" + (script == null ? "null" : "\"" + script + "\"") + "}";
        return mvc.perform(multipart("/api/contacts/import")
                .file(new MockMultipartFile("file", "dues.csv", "text/csv", csv))
                .file(new MockMultipartFile("options", "", "application/json", options.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    private static byte[] csv(String p1, String p2) {
        return ("Name,Mobile,Amount due\nRavi Kumar," + p1 + ",4000\nMeena Das," + p2 + ",2500\n").getBytes(StandardCharsets.UTF_8);
    }

    /** A script of this test's own, so approving it does not change what other tests see. */
    private String ownScript(String admin) throws Exception {
        String name = "Import test " + SEQ.incrementAndGet();
        mvc.perform(auth(post("/api/voice/scripts"), admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"name":"%s","kind":"OUTBOUND","model":"test-model","systemPrompt":"Remind them politely.",
                 "openingLine":"Hello, this is an AI assistant calling. Am I speaking with {{first_name}}?"}""".formatted(name)))
                .andExpect(status().is2xxSuccessful());
        approve(admin, name);
        return name;
    }

    private void approve(String admin, String scriptName) throws Exception {
        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/voice/scripts"), admin)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.name == '" + scriptName + "')].id");
        mvc.perform(auth(post("/api/voice/scripts/" + ids.get(0) + "/approve"), admin)).andExpect(status().isOk());
    }

    private static String phone() {
        return "9" + String.format("%09d", SEQ.incrementAndGet());
    }

    private String login(String email, String password) throws Exception {
        return JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.accessToken");
    }

    private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder auth(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
