package com.aicallcrm.crm.voice;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import com.jayway.jsonpath.JsonPath;

/**
 * With the CRM's calling hours at 9 AM to 6 PM, a campaign saved with wider hours (say 8 AM to 8 PM, from before the
 * change) shows, and is held to, 9 AM to 6 PM; the overview shows the CRM's own hours.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = {"app.voice.window-start=09:00", "app.voice.window-end=18:00"})
class CallingHoursIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Test
    void aCampaignWithWiderHoursShowsTheHoursItReallyCallsIn() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"AdminPass123!\"}"))
                .andExpect(status().isOk()).andReturn();
        String admin = "Bearer " + JsonPath.read(login.getResponse().getContentAsString(), "$.accessToken");

        mvc.perform(get("/api/voice/overview").header(HttpHeaders.AUTHORIZATION, admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.windowStart").value("09:00:00"))
                .andExpect(jsonPath("$.windowEnd").value("18:00:00"));

        MvcResult wide = mvc.perform(post("/api/voice/campaigns").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hours test wide\",\"scriptName\":\"Payment reminder\","
                                + "\"windowStart\":\"08:00\",\"windowEnd\":\"20:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaign.windowStart").value("09:00:00"))
                .andExpect(jsonPath("$.campaign.windowEnd").value("18:00:00"))
                .andReturn();
        Number id = JsonPath.read(wide.getResponse().getContentAsString(), "$.campaign.id");
        mvc.perform(get("/api/voice/campaigns/" + id).header(HttpHeaders.AUTHORIZATION, admin))
                .andExpect(jsonPath("$.campaign.windowEnd").value("18:00:00"));

        // Narrower hours are kept as they are.
        mvc.perform(post("/api/voice/campaigns").header(HttpHeaders.AUTHORIZATION, admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Hours test narrow\",\"scriptName\":\"Payment reminder\","
                                + "\"windowStart\":\"10:00\",\"windowEnd\":\"13:00\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.campaign.windowStart").value("10:00:00"))
                .andExpect(jsonPath("$.campaign.windowEnd").value("13:00:00"));
    }
}
