package com.aicallcrm.crm.settings;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.aicallcrm.crm.settings.Features.Feature;
import com.jayway.jsonpath.JsonPath;

/** An installation that bought only the import: the AI agent and the rest answer 403 even to the admin. */
@SpringBootTest(properties = "app.features=IMPORT")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class FeaturesTest {

    @Autowired
    MockMvc mvc;

    @Test
    void parsesTheList() {
        assertThat(Features.parse("ALL")).containsExactlyInAnyOrder(Feature.values());
        assertThat(Features.parse(" import , export ")).containsExactlyInAnyOrder(Feature.IMPORT, Feature.EXPORT);
        assertThatThrownBy(() -> Features.parse("IMPORT,AI_CALL")).hasMessageContaining("AI_CALL");
    }

    @Test
    void switchedOffModulesAreClosed() throws Exception {
        String token = JsonPath.read(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"admin@test.local\",\"password\":\"AdminPass123!\"}"))
                .andReturn().getResponse().getContentAsString(), "$.accessToken");
        String bearer = "Bearer " + token;
        mvc.perform(get("/api/public/branding")).andExpect(jsonPath("$.features").value(Matchers.contains("IMPORT")));
        mvc.perform(get("/api/voice/scripts").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.detail").value("This feature is not part of your plan"));
        mvc.perform(get("/api/faq").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/contacts/export").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
        mvc.perform(get("/api/follow-ups/mine").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isForbidden());
        // What was bought still works, and so does everything that is not a module.
        mvc.perform(get("/api/contacts/imports").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/contacts").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
        mvc.perform(get("/api/dashboard").header(HttpHeaders.AUTHORIZATION, bearer)).andExpect(status().isOk());
    }
}
