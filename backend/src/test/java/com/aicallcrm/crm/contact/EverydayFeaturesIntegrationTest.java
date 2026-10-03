package com.aicallcrm.crm.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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

import com.jayway.jsonpath.JsonPath;

/** Tags, bulk changes, follow-up reminders, and finding and merging duplicates. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class EverydayFeaturesIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(51000);

    @Autowired
    MockMvc mvc;

    @Autowired
    FollowUpReminders reminders;

    @Test
    void tagsKeepOneSpellingAndFilterTheList() throws Exception {
        String admin = admin();
        String tag = "Hot Lead " + SEQ.incrementAndGet();
        long a = contact(admin, "Tag One");
        long b = contact(admin, "Tag Two");
        mvc.perform(auth(put("/api/contacts/" + a + "/tags"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"  " + tag + " \",\"VIP\"]}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.tags").value(Matchers.containsInAnyOrder(tag, "VIP")));
        // Typed differently later: lands on the same tag, with the first spelling.
        mvc.perform(auth(put("/api/contacts/" + b + "/tags"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"" + tag.toUpperCase() + "\"]}")).andExpect(jsonPath("$.tags[0]").value(tag));
        mvc.perform(auth(get("/api/contacts").param("tag", tag.toLowerCase()), admin)).andExpect(jsonPath("$.totalItems").value(2));
        mvc.perform(auth(get("/api/tags"), admin))
                .andExpect(jsonPath("$[?(@.tag == '" + tag + "')].contacts").value(Matchers.hasItem(2)));
        // Removing works the same way.
        mvc.perform(auth(put("/api/contacts/" + a + "/tags"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"vip\"]}")).andExpect(jsonPath("$.tags").value(Matchers.contains("VIP")));
        mvc.perform(auth(put("/api/contacts/" + a + "/tags"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"" + "x".repeat(41) + "\"]}")).andExpect(status().isBadRequest());
    }

    @Test
    void anImportColumnBecomesTags() throws Exception {
        String admin = admin();
        String p = phone();
        String tag = "Expo " + SEQ.incrementAndGet();
        byte[] csv = ("Name,Mobile,Tags\nTagged Person," + p + ",\"" + tag + "; Chennai\"\n").getBytes(StandardCharsets.UTF_8);
        mvc.perform(multipart("/api/contacts/import/preview").file(new MockMultipartFile("file", "t.csv", "text/csv", csv))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(jsonPath("$.suggested.Tags").value("tags"));
        String options = "{\"mapping\":{\"Name\":\"name\",\"Mobile\":\"phone\",\"Tags\":\"tags\"},\"updateExisting\":false}";
        mvc.perform(multipart("/api/contacts/import").file(new MockMultipartFile("file", "t.csv", "text/csv", csv))
                .file(new MockMultipartFile("options", "", "application/json", options.getBytes(StandardCharsets.UTF_8)))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/contacts").param("q", p), admin))
                .andExpect(jsonPath("$.items[0].tags").value(Matchers.containsInAnyOrder(tag, "Chennai")));
    }

    @Test
    void bulkChangesByTickOrByFilter() throws Exception {
        String admin = admin();
        String agentEmail = user(admin, "AGENT");
        String agent = login(agentEmail);
        long agentId = userId(admin, agentEmail);
        String tag = "Bulk " + SEQ.incrementAndGet();
        long a = contact(admin, "Bulk One");
        long b = contact(admin, "Bulk Two");
        long c = contact(admin, "Bulk Three");

        mvc.perform(auth(post("/api/contacts/bulk"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ids\":[" + a + "," + b + "],\"action\":\"ADD_TAG\",\"tag\":\"" + tag + "\"}"))
                .andExpect(jsonPath("$.matched").value(2)).andExpect(jsonPath("$.changed").value(2));
        // Everyone matching the filter: the two tagged ones, not the third.
        mvc.perform(auth(post("/api/contacts/bulk"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"tag\":\"" + tag + "\"},\"action\":\"STAGE\",\"stage\":\"INTERESTED\"}"))
                .andExpect(jsonPath("$.changed").value(2));
        mvc.perform(auth(post("/api/contacts/bulk"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"tag\":\"" + tag + "\"},\"action\":\"ASSIGN\",\"userId\":" + agentId + "}"))
                .andExpect(jsonPath("$.changed").value(2));
        mvc.perform(auth(get("/api/contacts/" + a), admin)).andExpect(jsonPath("$.status").value("INTERESTED"))
                .andExpect(jsonPath("$.assignedTo.id").value(agentId));
        mvc.perform(auth(get("/api/contacts/" + c), admin)).andExpect(jsonPath("$.status").value("NEW"));
        mvc.perform(auth(get("/api/contacts/" + a + "/activities"), admin))
                .andExpect(jsonPath("$[*].type").value(Matchers.hasItems("STATUS_CHANGE", "ASSIGNMENT")));
        // Doing it twice changes nothing more.
        mvc.perform(auth(post("/api/contacts/bulk"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"filter\":{\"tag\":\"" + tag + "\"},\"action\":\"STAGE\",\"stage\":\"INTERESTED\"}"))
                .andExpect(jsonPath("$.matched").value(2)).andExpect(jsonPath("$.changed").value(0));

        mvc.perform(auth(post("/api/contacts/bulk"), agent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + a + "],\"action\":\"REMOVE_TAG\",\"tag\":\"" + tag + "\"}")).andExpect(status().isForbidden());
        mvc.perform(auth(post("/api/contacts/bulk"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"action\":\"ADD_TAG\",\"tag\":\"x\"}")).andExpect(status().isBadRequest());
        mvc.perform(auth(post("/api/contacts/bulk"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + a + "],\"action\":\"STAGE\",\"stage\":\"NOPE\"}")).andExpect(status().isBadRequest());
    }

    @Test
    void theAssigneeIsRemindedOnceWhenAFollowUpFallsDue() throws Exception {
        String admin = admin();
        String agentEmail = user(admin, "AGENT");
        String agent = login(agentEmail);
        long c = contact(agent, "Remind Me");
        String due = Instant.now().plus(5, ChronoUnit.MINUTES).toString();
        mvc.perform(auth(post("/api/contacts/" + c + "/follow-ups"), agent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"dueAt\":\"" + due + "\",\"purpose\":\"Send the price list\"}")).andExpect(status().isCreated());

        assertThat(reminders.send()).isGreaterThanOrEqualTo(1);
        mvc.perform(auth(get("/api/notifications"), agent))
                .andExpect(jsonPath("$[?(@.type == 'FOLLOW_UP_DUE')].title").value(Matchers.hasItem("Follow-up due: Remind Me")));
        assertThat(reminders.send()).isZero();
    }

    @Test
    void duplicatesAreFoundDismissedAndMerged() throws Exception {
        String admin = admin();
        String email = "dup" + SEQ.incrementAndGet() + "@example.com";
        String keepPhone = phone(), otherPhone = phone();
        long keep = id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Anita Rao\",\"phone\":\"" + keepPhone + "\",\"email\":\"" + email + "\"}")).andReturn());
        long other = id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Anita R\",\"phone\":\"" + otherPhone + "\",\"email\":\"" + email.toUpperCase()
                        + "\",\"notes\":\"Prefers mornings\"}")).andReturn());
        mvc.perform(auth(put("/api/contacts/" + other + "/tags"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"From expo\"]}")).andExpect(status().isOk());
        mvc.perform(auth(post("/api/contacts/" + other + "/status"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DO_NOT_CALL\"}")).andExpect(status().isOk());

        String path = "$[?(@.contacts[*].id contains " + keep + ")]";
        mvc.perform(auth(get("/api/contacts/duplicates"), admin))
                .andExpect(jsonPath(path + ".reasons[*]").value(Matchers.hasItem("Same email")));

        // "Not the same person" hides the pair...
        mvc.perform(auth(post("/api/contacts/duplicates/dismiss"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"ids\":[" + keep + "," + other + "]}")).andExpect(status().isOk());
        assertThat(groupsWith(admin, keep)).isZero();

        // ...but they can still be merged by hand. Everything moves; "Do not call" wins.
        mvc.perform(auth(post("/api/contacts/" + keep + "/merge"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"otherId\":" + other + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.altPhone").value(otherPhone))
                .andExpect(jsonPath("$.status").value("DO_NOT_CALL"))
                .andExpect(jsonPath("$.tags").value(Matchers.hasItem("From expo")))
                .andExpect(jsonPath("$.notes").value("Prefers mornings"));
        mvc.perform(auth(get("/api/contacts/" + other), admin)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/contacts/" + keep + "/activities"), admin))
                .andExpect(jsonPath("$[*].type").value(Matchers.hasItem("MERGE")))
                // The other contact's history came along.
                .andExpect(jsonPath("$[*].outcome").value(Matchers.hasItem("New → Do not call")));

        mvc.perform(auth(post("/api/contacts/" + keep + "/merge"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"otherId\":" + keep + "}")).andExpect(status().isBadRequest());
        String agent = login(user(admin, "AGENT"));
        mvc.perform(auth(get("/api/contacts/duplicates"), agent)).andExpect(status().isForbidden());
    }

    @Test
    void sameNameAndAlternatePhoneCountAsDuplicates() throws Exception {
        String admin = admin();
        String shared = phone();
        String name = "Karthik Subramanian " + SEQ.incrementAndGet();
        long a = id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + name + "\",\"phone\":\"" + shared + "\"}")).andReturn());
        mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + name.toUpperCase() + "\",\"phone\":\"" + phone() + "\",\"altPhone\":\"" + shared + "\"}"))
                .andExpect(status().isCreated());
        mvc.perform(auth(get("/api/contacts/duplicates"), admin))
                .andExpect(jsonPath("$[?(@.contacts[*].id contains " + a + ")].reasons[*]")
                        .value(Matchers.hasItems("Same phone number", "Same name")));
    }

    // ------------------------------------------------------------------ helpers

    private int groupsWith(String token, long id) throws Exception {
        List<?> groups = JsonPath.read(mvc.perform(auth(get("/api/contacts/duplicates"), token)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.contacts[*].id contains " + id + ")]");
        return groups.size();
    }

    private long contact(String token, String name) throws Exception {
        return id(mvc.perform(auth(post("/api/contacts"), token).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"" + name + "\",\"phone\":\"" + phone() + "\"}")).andExpect(status().isCreated()).andReturn());
    }

    private static String phone() {
        return "9" + String.format("%09d", SEQ.incrementAndGet());
    }

    private String user(String adminToken, String role) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@everyday.test";
        mvc.perform(auth(post("/api/users"), adminToken).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"%s user","email":"%s","role":"%s","password":"StaffPass123!"}""".formatted(role, email, role)))
                .andExpect(status().isCreated());
        return email;
    }

    private long userId(String adminToken, String email) throws Exception {
        List<Integer> ids = JsonPath.read(mvc.perform(auth(get("/api/users"), adminToken)).andReturn().getResponse()
                .getContentAsString(), "$[?(@.email == '" + email + "')].id");
        return ids.get(0);
    }

    private String admin() throws Exception {
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

    private static long id(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }
}
