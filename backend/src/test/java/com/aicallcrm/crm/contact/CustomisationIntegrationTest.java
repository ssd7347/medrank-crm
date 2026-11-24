package com.aicallcrm.crm.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/** The customisation base: stages each customer names, the logo and login wording, and starter packs. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class CustomisationIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(91000);
    private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', 0x0d, 0x0a, 0x1a, 0x0a, 0, 0, 0, 0x0d};

    @Autowired
    MockMvc mvc;

    @Test
    void customStagesCanBeAddedUsedAndRemoved() throws Exception {
        String admin = login();
        String label = "Visited " + SEQ.incrementAndGet();
        String body = "{\"label\":\"" + label + "\",\"color\":\"#7c3aed\",\"category\":\"OPEN\"}";

        String stage = mvc.perform(auth(post("/api/stages"), admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String key = JsonPath.read(stage, "$.key");
        long stageId = ((Number) JsonPath.read(stage, "$.id")).longValue();
        assertThat(key).startsWith("VISITED_");
        // Same name again: the existing stage comes back, no duplicate.
        mvc.perform(auth(post("/api/stages"), admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.id").value(stageId));

        long contact = id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Stage Test\",\"phone\":\"" + phone() + "\"}")).andExpect(status().isCreated()).andReturn());
        mvc.perform(auth(post("/api/contacts/" + contact + "/status"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"NO_SUCH_STAGE\"}")).andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/contacts/" + contact + "/status"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"" + key + "\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value(key));
        // The timeline speaks the customer's words, not the key.
        mvc.perform(auth(get("/api/contacts/" + contact + "/activities"), admin))
                .andExpect(jsonPath("$[0].outcome").value("New → " + label));
        mvc.perform(auth(get("/api/contacts").param("status", key), admin)).andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(auth(get("/api/dashboard"), admin)).andExpect(jsonPath("$.contactsByStatus." + key).value(1));

        // Built-in stages can be renamed but never deleted: the AI agent relies on them.
        long newStage = stageId("NEW", admin);
        mvc.perform(auth(delete("/api/stages/" + newStage), admin)).andExpect(status().isConflict());
        // A stage with contacts needs somewhere to move them.
        mvc.perform(auth(delete("/api/stages/" + stageId), admin)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("Choose a stage")));
        mvc.perform(auth(delete("/api/stages/" + stageId).param("moveTo", "INTERESTED"), admin)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/contacts/" + contact), admin)).andExpect(jsonPath("$.status").value("INTERESTED"));
    }

    @Test
    void onlyTheAdminChangesStagesAndTheOrderListsEveryStage() throws Exception {
        String admin = login();
        String manager = login(user(admin, "MANAGER"));
        mvc.perform(auth(get("/api/stages"), manager)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == 'DO_NOT_CALL')].builtIn").value(Matchers.hasItem(true)));
        mvc.perform(auth(post("/api/stages"), manager).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Nope\",\"color\":\"#7c3aed\",\"category\":\"OPEN\"}")).andExpect(status().isForbidden());

        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/stages"), admin)).andReturn().getResponse()
                .getContentAsString(), "$[*].id");
        mvc.perform(auth(put("/api/stages/order"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":" + ids.subList(1, ids.size()) + "}")).andExpect(status().isBadRequest());
        List<Integer> reversed = new java.util.ArrayList<>(ids);
        java.util.Collections.reverse(reversed);
        mvc.perform(auth(put("/api/stages/order"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":" + reversed + "}")).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(reversed.get(0)));
        mvc.perform(auth(put("/api/stages/order"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":" + ids + "}")).andExpect(status().isOk());

        // Renaming a built-in stage keeps its meaning (category) for reports and the AI agent.
        long dnc = stageId("DO_NOT_CALL", admin);
        mvc.perform(auth(put("/api/stages/" + dnc), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"label\":\"Never call\",\"color\":\"#dc2626\",\"category\":\"WON\"}"))
                .andExpect(jsonPath("$.label").value("Never call")).andExpect(jsonPath("$.category").value("LOST"));
        mvc.perform(auth(put("/api/stages/" + dnc), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"label\":\"Do not call\",\"color\":\"#dc2626\",\"category\":\"LOST\"}")).andExpect(status().isOk());
    }

    @Test
    void logoAndLoginWordingAreShownBeforeSignIn() throws Exception {
        String admin = login();
        mvc.perform(get("/api/public/branding")).andExpect(jsonPath("$.features").value(Matchers.hasItem("AI_CALLS")));

        // Only real images: a script disguised as a PNG is refused.
        mvc.perform(auth(multipart("/api/settings/logo").file(new MockMultipartFile("file", "logo.svg", "image/png",
                "<svg onload=alert(1)>".getBytes())), admin)).andExpect(status().isBadRequest());
        mvc.perform(auth(multipart("/api/settings/logo").file(new MockMultipartFile("file", "big.png", "image/png",
                new byte[400 * 1024])), admin)).andExpect(status().isBadRequest());
        String manager = login(user(admin, "MANAGER"));
        mvc.perform(auth(multipart("/api/settings/logo").file(new MockMultipartFile("file", "logo.png", "image/png", PNG)),
                manager)).andExpect(status().isForbidden());

        String logoUrl = JsonPath.read(mvc.perform(auth(multipart("/api/settings/logo")
                        .file(new MockMultipartFile("file", "logo.png", "image/png", PNG)), admin))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(), "$.logoUrl");
        assertThat(logoUrl).startsWith("/api/public/logo?v=");
        mvc.perform(get("/api/public/logo")).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_TYPE, "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));
        mvc.perform(auth(delete("/api/settings/logo"), admin)).andExpect(jsonPath("$.logoUrl").doesNotExist());
        mvc.perform(get("/api/public/logo")).andExpect(status().isNotFound());

        String current = mvc.perform(get("/api/public/branding")).andReturn().getResponse().getContentAsString();
        String body = """
                {"companyName":"%s","agentName":"%s","brandColor":"%s","contactLabel":"%s",
                 "loginHeadline":"Every patient called on time","loginText":"Sign in to see today's calls."}"""
                .formatted(JsonPath.read(current, "$.companyName"), JsonPath.read(current, "$.agentName"),
                        JsonPath.read(current, "$.brandColor"), JsonPath.read(current, "$.contactLabel"));
        mvc.perform(auth(put("/api/settings/branding"), admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/branding")).andExpect(jsonPath("$.loginHeadline").value("Every patient called on time"));
    }

    @Test
    void starterPacksOnlyAddAndCanBeLoadedTwice() throws Exception {
        String admin = login();
        mvc.perform(auth(get("/api/settings/packs"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[*].id").value(Matchers.hasItems("clinic", "finance", "real_estate", "education",
                        "insurance")));
        String manager = login(user(admin, "MANAGER"));
        mvc.perform(auth(post("/api/settings/packs/finance/apply"), manager)).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/settings/packs/nope/apply"), admin)).andExpect(status().isNotFound());

        mvc.perform(auth(post("/api/settings/packs/finance/apply"), admin)).andExpect(status().isOk());
        // A second load finds everything already there and adds nothing.
        mvc.perform(auth(post("/api/settings/packs/finance/apply"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stagesAdded").value(0)).andExpect(jsonPath("$.fieldsAdded").value(0))
                .andExpect(jsonPath("$.scriptsAdded").value(0)).andExpect(jsonPath("$.faqsAdded").value(0));

        mvc.perform(auth(get("/api/stages"), admin)).andExpect(jsonPath("$[*].label").value(Matchers.hasItem("Promised to pay")));
        mvc.perform(auth(get("/api/fields"), admin))
                .andExpect(jsonPath("$[?(@.key == 'loan_account_number')].verifyWith").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.key == 'loan_account_number')].agentAccess").value(Matchers.hasItem("NONE")));
        // Scripts arrive as drafts and answers switched off: both still have [placeholders] to fill in.
        mvc.perform(auth(get("/api/voice/scripts"), admin))
                .andExpect(jsonPath("$[?(@.name == 'EMI due reminder')].approved").value(Matchers.hasItem(false)))
                .andExpect(jsonPath("$[?(@.name == 'EMI due reminder')].systemPrompt").value(
                        Matchers.hasItem(Matchers.containsString("never threaten"))));
        mvc.perform(auth(get("/api/faq"), admin))
                .andExpect(jsonPath("$[?(@.question == 'How can I pay my EMI?')].active").value(Matchers.hasItem(false)));
        mvc.perform(get("/api/public/branding")).andExpect(jsonPath("$.industryPack").value("finance"));
    }

    @Test
    void theNeetCounsellingPackIsListedFirstAndKeepsPrivateDetailsPrivate() throws Exception {
        String admin = login();
        mvc.perform(auth(get("/api/settings/packs"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value("neet_counselling"))
                .andExpect(jsonPath("$[0].contactLabel").value("Student"));
        mvc.perform(auth(post("/api/settings/packs/neet_counselling/apply"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.stagesAdded").value(5)).andExpect(jsonPath("$.fieldsAdded").value(13))
                .andExpect(jsonPath("$.scriptsAdded").value(5)).andExpect(jsonPath("$.faqsAdded").value(6));
        mvc.perform(auth(get("/api/stages"), admin)).andExpect(jsonPath("$[*].label").value(
                Matchers.hasItems("Counselling session booked", "Seat allotted", "Admitted")));
        // The roll number proves who is speaking but is never said; category and state are never sent to the AI;
        // score, rank and the deadlines only once the caller has proved who they are.
        mvc.perform(auth(get("/api/fields"), admin))
                .andExpect(jsonPath("$[?(@.key == 'neet_roll_number')].verifyWith").value(Matchers.hasItem(true)))
                .andExpect(jsonPath("$[?(@.key == 'neet_roll_number')].agentAccess").value(Matchers.hasItem("NONE")))
                .andExpect(jsonPath("$[?(@.key == 'category')].agentAccess").value(Matchers.hasItem("NONE")))
                .andExpect(jsonPath("$[?(@.key == 'domicile_state')].agentAccess").value(Matchers.hasItem("NONE")))
                .andExpect(jsonPath("$[?(@.key == 'neet_score')].agentAccess").value(Matchers.hasItem("AFTER_VERIFY")))
                .andExpect(jsonPath("$[?(@.key == 'neet_all_india_rank')].agentAccess").value(Matchers.hasItem("AFTER_VERIFY")))
                .andExpect(jsonPath("$[?(@.key == 'choice_filling_deadline')].fieldType").value(Matchers.hasItem("DATE")));
        // Drafts to approve, answers switched off: both still hold [placeholders] only MedRank can fill in.
        mvc.perform(auth(get("/api/voice/scripts"), admin))
                .andExpect(jsonPath("$[?(@.name == 'Counselling round reminder')].approved").value(Matchers.hasItem(false)))
                .andExpect(jsonPath("$[?(@.name == 'NEET enquiry follow-up')].systemPrompt").value(
                        Matchers.hasItem(Matchers.containsString("Never promise or predict a seat"))));
        mvc.perform(auth(get("/api/faq"), admin))
                .andExpect(jsonPath("$[?(@.question == 'Can you guarantee a seat?')].active").value(Matchers.hasItem(false)));
        mvc.perform(get("/api/public/branding")).andExpect(jsonPath("$.industryPack").value("neet_counselling"));
    }

    // ------------------------------------------------------------------ helpers

    private long stageId(String key, String token) throws Exception {
        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/stages"), token)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.key == '" + key + "')].id");
        return ids.get(0);
    }

    private static String phone() {
        return "9" + String.format("%09d", SEQ.incrementAndGet());
    }

    private String user(String adminToken, String role) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@custom.test";
        mvc.perform(auth(post("/api/users"), adminToken).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"%s user","email":"%s","role":"%s","password":"StaffPass123!"}""".formatted(role, email, role)))
                .andExpect(status().isCreated());
        return email;
    }

    private String login() throws Exception {
        return login("admin@test.local", "AdminPass123!");
    }

    private String login(String email) throws Exception {
        return login(email, "StaffPass123!");
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.accessToken");
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }

    private static MockMultipartHttpServletRequestBuilder auth(MockMultipartHttpServletRequestBuilder b, String token) {
        b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
        return b;
    }

    private static long id(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
