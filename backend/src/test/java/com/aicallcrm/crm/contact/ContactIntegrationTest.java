package com.aicallcrm.crm.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
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

/**
 * Contacts end to end: custom fields, who sees what, the timeline, importing Excel and CSV files with
 * whatever columns the customer has, exporting, and the white-label settings.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ContactIntegrationTest {

    private static final AtomicInteger SEQ = new AtomicInteger(81000);

    @Autowired
    MockMvc mvc;

    @Test
    void contactsCustomFieldsAndWhoSeesWhat() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String agentEmail = user(admin, "AGENT");
        String agent = login(agentEmail, "StaffPass123!");
        String otherAgent = login(user(admin, "AGENT"), "StaffPass123!");
        String manager = login(user(admin, "MANAGER"), "StaffPass123!");
        long agentId = userId(admin, agentEmail);

        // Only the admin defines fields; anyone can read them.
        String field = "{\"label\":\"Policy Number " + SEQ.incrementAndGet() + "\",\"fieldType\":\"TEXT\","
                + "\"agentAccess\":\"AFTER_VERIFY\",\"verifyWith\":true,\"sortOrder\":10,\"active\":true}";
        mvc.perform(auth(post("/api/fields"), manager).contentType(MediaType.APPLICATION_JSON).content(field))
                .andExpect(status().isForbidden());
        String key = JsonPath.read(mvc.perform(auth(post("/api/fields"), admin).contentType(MediaType.APPLICATION_JSON)
                .content(field)).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.key");
        assertThat(key).startsWith("policy_number_");
        mvc.perform(auth(get("/api/fields"), agent)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.key == '" + key + "')].verifyWith").value(Matchers.hasItem(true)));

        String phone = phone();
        long mine = id(mvc.perform(auth(post("/api/contacts"), agent).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Kiran Rao\",\"phone\":\"+91 " + phone + "\",\"fields\":{\"" + key
                                + "\":\"PN-778\"}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.phone").value(phone))
                .andExpect(jsonPath("$.status").value("NEW"))
                .andExpect(jsonPath("$.fields." + key).value("PN-778"))
                // An agent's own new contact is assigned to them.
                .andExpect(jsonPath("$.assignedTo.id").value(agentId))
                .andReturn());
        mvc.perform(auth(post("/api/contacts"), manager).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Copy\",\"phone\":\"" + phone + "\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.existingContactId").value(mine));
        mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fullName\":\"Bad\",\"phone\":\"" + phone() + "\",\"fields\":{\"no_such_field\":\"x\"}}"))
                .andExpect(status().isBadRequest());

        // Agents see their own and unassigned contacts; managers and the admin see all.
        mvc.perform(auth(get("/api/contacts/" + mine), otherAgent)).andExpect(status().isNotFound());
        mvc.perform(auth(get("/api/contacts/" + mine), manager)).andExpect(status().isOk());
        mvc.perform(auth(get("/api/contacts").param("q", "PN-778"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].id").value(mine));
        mvc.perform(auth(get("/api/contacts").param("q", phone), otherAgent)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(0));
        mvc.perform(auth(post("/api/contacts/" + mine + "/assign"), agent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"userId\":null}")).andExpect(status().isForbidden());

        // Status changes and notes land on the timeline.
        mvc.perform(auth(post("/api/contacts/" + mine + "/status"), agent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"INTERESTED\",\"note\":\"Wants a quote\"}")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("INTERESTED"));
        mvc.perform(auth(post("/api/contacts/" + mine + "/activities"), agent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"type\":\"NOTE\",\"notes\":\"Prefers mornings\"}")).andExpect(status().isCreated());
        mvc.perform(auth(get("/api/contacts/" + mine + "/activities"), agent)).andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'STATUS_CHANGE')].outcome").value(Matchers.hasItem("New → Interested")))
                .andExpect(jsonPath("$[?(@.type == 'NOTE')]").isNotEmpty());

        // A staff call is logged against the contact.
        mvc.perform(auth(post("/api/calls"), agent).contentType(MediaType.APPLICATION_JSON)
                .content("{\"contactId\":" + mine + ",\"outcome\":\"NO_ANSWER\"}")).andExpect(status().isCreated());
        mvc.perform(auth(get("/api/contacts/" + mine + "/calls"), agent)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].outcome").value("NO_ANSWER"));
    }

    @Test
    void excelImportCreatesFieldsUpdatesExistingAndReportsBadRows() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String agent = login(user(admin, "AGENT"), "StaffPass123!");
        String existingPhone = phone();
        long existing = id(mvc.perform(auth(post("/api/contacts"), admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fullName\":\"Old Name\",\"phone\":\"" + existingPhone + "\"}")).andExpect(status().isCreated())
                .andReturn());
        String p1 = phone();
        String p2 = phone();
        String tag = "T" + SEQ.incrementAndGet();
        byte[] xlsx = workbook(List.of(
                List.of("Customer Name", "Mobile No", "Email", "Amount Due", "Due Date", "Import Tag"),
                List.of("Asha Menon", Double.parseDouble(p1), "asha@example.com", "₹ 12,500", LocalDate.of(2026, 11, 3), tag),
                List.of("Ravi Kumar", p2, "not-an-email", 4000.0, "05/11/2026", tag),
                List.of("New Name", existingPhone, "", "1,000", "", tag),
                List.of("No Phone", "", "", "", "", tag),
                List.of("Repeat Row", p1, "", "", "", tag)));
        MockMultipartFile file = new MockMultipartFile("file", "customers.xlsx",
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", xlsx);

        mvc.perform(multipart("/api/contacts/import/preview").file(file).header(HttpHeaders.AUTHORIZATION, "Bearer " + agent))
                .andExpect(status().isForbidden());
        MvcResult preview = mvc.perform(multipart("/api/contacts/import/preview").file(file)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalRows").value(5))
                .andExpect(jsonPath("$.suggested['Customer Name']").value("name"))
                .andExpect(jsonPath("$.suggested['Mobile No']").value("phone"))
                .andExpect(jsonPath("$.suggested['Email']").value("email"))
                .andExpect(jsonPath("$.suggested['Amount Due']").value("new:AMOUNT"))
                .andExpect(jsonPath("$.suggested['Due Date']").value("new:DATE"))
                // A phone number stored as a number in Excel must not come out as 9.87E9.
                .andExpect(jsonPath("$.sample[0][1]").value(p1))
                .andExpect(jsonPath("$.sample[0][4]").value("2026-11-03"))
                .andReturn();
        String mapping = new String(preview.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        java.util.Map<String, String> guessed = JsonPath.read(mapping, "$.suggested");
        String suggested = guessed.entrySet().stream().map(e -> "\"" + e.getKey() + "\":\"" + e.getValue() + "\"")
                .reduce((a, b) -> a + "," + b).orElse("");
        MockMultipartFile options = new MockMultipartFile("options", "", "application/json",
                ("{\"mapping\":{" + suggested + "},\"updateExisting\":true,\"aiCallConsent\":true,"
                        + "\"recordingConsent\":false,\"consentEvidence\":\"Signed form at the counter\"}")
                        .getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/contacts/import").file(file).file(options)
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created").value(2))
                .andExpect(jsonPath("$.updated").value(1))
                .andExpect(jsonPath("$.skipped").value(2))
                .andExpect(jsonPath("$.problems").value(Matchers.hasItem(Matchers.containsString("Row 5: no phone number"))))
                .andExpect(jsonPath("$.problems").value(Matchers.hasItem(Matchers.containsString("Row 6: same phone number"))))
                .andExpect(jsonPath("$.problems").value(Matchers.hasItem(Matchers.containsString("Row 3: email"))));

        MvcResult asha = mvc.perform(auth(get("/api/contacts").param("q", p1), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(1))
                .andExpect(jsonPath("$.items[0].fullName").value("Asha Menon"))
                .andExpect(jsonPath("$.items[0].fields.amount_due").value("12500"))
                .andExpect(jsonPath("$.items[0].fields.due_date").value("2026-11-03"))
                .andReturn();
        long ashaId = ((Number) JsonPath.read(asha.getResponse().getContentAsString(), "$.items[0].id")).longValue();
        mvc.perform(auth(get("/api/contacts/" + existing), admin))
                .andExpect(jsonPath("$.fullName").value("New Name"))
                .andExpect(jsonPath("$.fields.amount_due").value("1000"));
        // "These people agreed to AI calls" was ticked, with proof: consent is on record.
        mvc.perform(auth(get("/api/contacts/" + ashaId + "/voice-consent"), admin))
                .andExpect(jsonPath("$[0].state").value("GRANTED"))
                .andExpect(jsonPath("$[0].source").value("IMPORTED_LIST"))
                .andExpect(jsonPath("$[0].recording").value(false));
        mvc.perform(auth(get("/api/contacts/imports"), admin)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].fileName").value("customers.xlsx"));
        mvc.perform(auth(get("/api/fields"), admin))
                .andExpect(jsonPath("$[?(@.key == 'amount_due')].fieldType").value(Matchers.hasItem("AMOUNT")));

        // Export what was imported, custom columns included.
        mvc.perform(auth(get("/api/contacts/export").param("q", tag), agent)).andExpect(status().isForbidden());
        String csv = mvc.perform(auth(get("/api/contacts/export").param("q", tag), admin)).andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, Matchers.containsString("contacts-")))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("Amount Due").contains("Asha Menon").contains("12500").contains(p2);
    }

    @Test
    void csvImportWorksTooAndNeedsNameAndPhoneColumns() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String phone = phone();
        MockMultipartFile file = new MockMultipartFile("file", "list.csv", "text/csv",
                ("Name,Phone,Plan\nMeena,0" + phone + ",Gold\n").getBytes(StandardCharsets.UTF_8));
        MockMultipartFile noPhone = new MockMultipartFile("options", "", "application/json",
                "{\"mapping\":{\"Name\":\"name\"}}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/contacts/import").file(file).file(noPhone).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isBadRequest());
        MockMultipartFile options = new MockMultipartFile("options", "", "application/json",
                "{\"mapping\":{\"Name\":\"name\",\"Phone\":\"phone\",\"Plan\":\"new:TEXT\"}}".getBytes(StandardCharsets.UTF_8));
        mvc.perform(multipart("/api/contacts/import").file(file).file(options).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$.created").value(1));
        mvc.perform(auth(get("/api/contacts").param("q", phone), admin))
                .andExpect(jsonPath("$.items[0].phone").value(phone))
                .andExpect(jsonPath("$.items[0].fields.plan").value("Gold"));
        MockMultipartFile junk = new MockMultipartFile("file", "photo.xlsx", "application/octet-stream", new byte[] {1, 2, 3});
        mvc.perform(multipart("/api/contacts/import/preview").file(junk).header(HttpHeaders.AUTHORIZATION, "Bearer " + admin))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(Matchers.containsString("could not be read")));
    }

    @Test
    void brandingIsPublicButOnlyTheAdminChangesIt() throws Exception {
        String admin = login("admin@test.local", "AdminPass123!");
        String manager = login(user(admin, "MANAGER"), "StaffPass123!");
        mvc.perform(get("/api/public/branding")).andExpect(status().isOk())
                .andExpect(jsonPath("$.agentName").isNotEmpty());
        String body = "{\"companyName\":\"Sunrise Clinic\",\"agentName\":\"Maya\",\"brandColor\":\"#1d4ed8\","
                + "\"contactLabel\":\"Patient\"}";
        mvc.perform(auth(put("/api/settings/branding"), manager).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isForbidden());
        mvc.perform(auth(put("/api/settings/branding"), admin).contentType(MediaType.APPLICATION_JSON)
                .content(body.replace("#1d4ed8", "blue"))).andExpect(status().isBadRequest());
        mvc.perform(auth(put("/api/settings/branding"), admin).contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());
        mvc.perform(get("/api/public/branding")).andExpect(jsonPath("$.companyName").value("Sunrise Clinic"))
                .andExpect(jsonPath("$.contactLabel").value("Patient"));
    }

    // ------------------------------------------------------------------ helpers

    private static byte[] workbook(List<List<Object>> rows) throws Exception {
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = wb.createSheet("Customers");
            CellStyle dateStyle = wb.createCellStyle();
            dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd-mm-yyyy"));
            for (int r = 0; r < rows.size(); r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows.get(r).size(); c++) {
                    Object v = rows.get(r).get(c);
                    var cell = row.createCell(c);
                    if (v instanceof Double d) {
                        cell.setCellValue(d);
                    } else if (v instanceof LocalDate d) {
                        cell.setCellValue(d);
                        cell.setCellStyle(dateStyle);
                    } else {
                        cell.setCellValue(String.valueOf(v));
                    }
                }
            }
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static String phone() {
        return "9" + String.format("%09d", SEQ.incrementAndGet());
    }

    private String user(String adminToken, String role) throws Exception {
        String email = role.toLowerCase() + SEQ.incrementAndGet() + "@contact.test";
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
