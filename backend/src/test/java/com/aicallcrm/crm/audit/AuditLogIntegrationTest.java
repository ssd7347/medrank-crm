package com.aicallcrm.crm.audit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.jayway.jsonpath.JsonPath;

/** The admin can read the audit log: newest first, by action, staff member, contact and date; nobody else can. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    MockMvc mvc;

    @Test
    void theAdminReadsWhoDidWhatAndNobodyElseCan() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String email = "audit.manager" + SEQ.incrementAndGet() + "@audit.test";
        long managerId = id(mvc.perform(auth(post("/api/users"), admin).contentType(MediaType.APPLICATION_JSON).content("""
                {"fullName":"Meera Auditwala","email":"%s","role":"MANAGER","password":"StaffPass123!"}""".formatted(email)))
                .andExpect(status().isCreated()).andReturn());
        String manager = login(email, "StaffPass123!");
        long mine = contact(admin, "Audit Person One");
        long hers = contact(manager, "Audit Person Two");
        mvc.perform(auth(put("/api/contacts/" + mine + "/tags"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"tags\":[\"Audit\"]}")).andExpect(status().isOk());
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + email + "\",\"password\":\"WrongPass999!\"}")).andExpect(status().is4xxClientError());

        // Only the admin.
        mvc.perform(auth(get("/api/audit"), manager)).andExpect(status().isForbidden());
        mvc.perform(auth(get("/api/audit/actions"), manager)).andExpect(status().isForbidden());

        // One contact's entries, newest first, with who did it and a link to the contact.
        mvc.perform(auth(get("/api/audit").param("contact", String.valueOf(mine)), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[*].entityId").value(Matchers.everyItem(Matchers.is((int) mine))))
                .andExpect(jsonPath("$.items[*].action").value(Matchers.contains("CONTACT_TAGGED", "CONTACT_CREATED")))
                .andExpect(jsonPath("$.items[-1].action").value("CONTACT_CREATED"))
                .andExpect(jsonPath("$.items[-1].by").value("Super Admin"))
                .andExpect(jsonPath("$.items[-1].link").value("/contacts/" + mine));
        // By staff member and action: the manager created only her contact.
        mvc.perform(auth(get("/api/audit").param("by", String.valueOf(managerId)).param("action", "CONTACT_CREATED"), admin))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].entityId").value((int) hers))
                .andExpect(jsonPath("$.items[0].by").value("Meera Auditwala"));
        // Her sign-in, and the failed try on her account (recorded under the account tried; the page says so).
        mvc.perform(auth(get("/api/audit").param("by", String.valueOf(managerId)).param("action", "LOGIN"), admin))
                .andExpect(jsonPath("$.totalItems").value(1));
        mvc.perform(auth(get("/api/audit").param("by", String.valueOf(managerId)).param("action", "LOGIN_FAILED"), admin))
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].by").value("Meera Auditwala"));
        mvc.perform(auth(get("/api/audit/actions"), admin))
                .andExpect(jsonPath("$").value(Matchers.hasItems("CONTACT_CREATED", "LOGIN", "LOGIN_FAILED", "USER_CREATED")));

        // Dates are Indian dates: today has them, yesterday does not; an end before the start is refused.
        LocalDate today = LocalDate.now(ZoneId.of("Asia/Kolkata"));
        mvc.perform(auth(get("/api/audit").param("by", String.valueOf(managerId)).param("from", today.toString())
                        .param("to", today.toString()), admin))
                .andExpect(jsonPath("$.totalItems").value(Matchers.greaterThanOrEqualTo(2)));
        mvc.perform(auth(get("/api/audit").param("by", String.valueOf(managerId)).param("to", today.minusDays(1).toString()), admin))
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(auth(get("/api/audit").param("from", today.toString()).param("to", today.minusDays(1).toString()), admin))
                .andExpect(status().isBadRequest());
        mvc.perform(auth(get("/api/audit").param("size", "201"), admin)).andExpect(status().isBadRequest());

        // Pages: one entry each, in order, adding up to the total.
        MvcResult p0 = mvc.perform(auth(get("/api/audit").param("contact", String.valueOf(mine)).param("size", "1"), admin))
                .andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        MvcResult p1 = mvc.perform(auth(get("/api/audit").param("contact", String.valueOf(mine)).param("size", "1")
                .param("page", "1"), admin)).andExpect(jsonPath("$.items.length()").value(1)).andReturn();
        int first = JsonPath.read(p0.getResponse().getContentAsString(), "$.items[0].id");
        int second = JsonPath.read(p1.getResponse().getContentAsString(), "$.items[0].id");
        org.assertj.core.api.Assertions.assertThat(first).isGreaterThan(second);
        int total = JsonPath.read(p0.getResponse().getContentAsString(), "$.totalItems");
        org.assertj.core.api.Assertions.assertThat((int) JsonPath.read(p0.getResponse().getContentAsString(), "$.totalPages"))
                .isEqualTo(total);

        // Opening an AI call is recorded with a link to the call.
        String call = JsonPath.read(mvc.perform(auth(post("/api/voice/test-calls"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contactId\":" + mine + ",\"scriptName\":\"Payment reminder\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.callId");
        mvc.perform(auth(get("/api/voice/calls/" + call), admin)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/audit").param("action", "VOICE_CALL_VIEWED").param("size", "1"), admin))
                .andExpect(jsonPath("$.items[0].link").value("/voice/calls/" + call));

        // Once the contact is erased its entries stay, without details and without a link.
        String phone = JsonPath.read(mvc.perform(auth(get("/api/contacts/" + mine), admin)).andReturn().getResponse()
                .getContentAsString(), "$.phone");
        mvc.perform(auth(post("/api/contacts/" + mine + "/erase"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"confirmPhone\":\"" + phone + "\",\"reason\":\"Audit test\"}")).andExpect(status().isOk());
        mvc.perform(auth(get("/api/audit").param("contact", String.valueOf(mine)), admin))
                .andExpect(jsonPath("$.items[*].action").value(Matchers.hasItems("CONTACT_CREATED", "CONTACT_ERASED")))
                .andExpect(jsonPath("$.items[*].link").value(Matchers.everyItem(Matchers.nullValue())));
    }

    private long contact(String token, String name) throws Exception {
        String phone = "66" + String.format("%08d", 41000 + SEQ.incrementAndGet());
        return id(mvc.perform(auth(post("/api/contacts"), token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"" + name + "\",\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isCreated()).andReturn());
    }

    private String login(String email, String password) throws Exception {
        MvcResult r = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return JsonPath.read(r.getResponse().getContentAsString(), "$.accessToken");
    }

    private static long id(MvcResult r) throws Exception {
        return ((Number) JsonPath.read(r.getResponse().getContentAsString(), "$.id")).longValue();
    }

    private static MockHttpServletRequestBuilder auth(MockHttpServletRequestBuilder b, String token) {
        return b.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    }
}
