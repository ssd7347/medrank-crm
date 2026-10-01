package com.aicallcrm.crm.contact;

import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.common.PageResponse;
import com.aicallcrm.crm.contact.ContactService.ActivityRequest;
import com.aicallcrm.crm.contact.ContactService.ActivityView;
import com.aicallcrm.crm.contact.ContactService.AssignRequest;
import com.aicallcrm.crm.contact.ContactService.ContactListItem;
import com.aicallcrm.crm.contact.ContactService.ContactRequest;
import com.aicallcrm.crm.contact.ContactService.ContactView;
import com.aicallcrm.crm.contact.ContactService.Filter;
import com.aicallcrm.crm.contact.ContactService.FollowUpRequest;
import com.aicallcrm.crm.contact.ContactService.FollowUpView;
import com.aicallcrm.crm.contact.ContactService.StatusRequest;
import com.aicallcrm.crm.contact.FieldService.FieldRequest;
import com.aicallcrm.crm.contact.FieldService.FieldView;
import com.aicallcrm.crm.contact.ImportService.BatchView;
import com.aicallcrm.crm.contact.ImportService.Options;
import com.aicallcrm.crm.contact.ImportService.Preview;
import com.aicallcrm.crm.contact.ImportService.Result;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class ContactController {

    private final ContactService contacts;
    private final FieldService fields;
    private final ImportService imports;

    public ContactController(ContactService contacts, FieldService fields, ImportService imports) {
        this.contacts = contacts;
        this.fields = fields;
        this.imports = imports;
    }

    // ------------------------------------------------------------------ contacts

    @GetMapping("/contacts")
    public PageResponse<ContactListItem> search(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) ContactStatus status,
                                                @RequestParam(required = false) Long assignedToId,
                                                @RequestParam(defaultValue = "false") boolean unassignedOnly,
                                                @RequestParam(required = false) Long importBatchId,
                                                @RequestParam(required = false) Long branchId,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "25") int size) {
        return contacts.search(new Filter(q, status, assignedToId, unassignedOnly, importBatchId, branchId), page, size);
    }

    /** Download what is on screen as a spreadsheet (CSV opens in Excel), custom fields included. */
    @GetMapping("/contacts/export")
    public ResponseEntity<byte[]> export(@RequestParam(required = false) String q,
                                         @RequestParam(required = false) ContactStatus status,
                                         @RequestParam(required = false) Long assignedToId,
                                         @RequestParam(defaultValue = "false") boolean unassignedOnly,
                                         @RequestParam(required = false) Long importBatchId,
                                         @RequestParam(required = false) Long branchId) throws Exception {
        if (!CurrentUser.get().seesAllContacts()) {
            throw ApiException.forbidden("Only an admin or a manager can export contacts");
        }
        List<FieldDefinition> defs = fields.definitions();
        List<String> head = new ArrayList<>(List.of("Name", "Phone", "Alternate phone", "Email", "Status", "Source",
                "Assigned to", "Notes"));
        defs.forEach(d -> head.add(d.getLabel()));
        StringWriter out = new StringWriter();
        try (CSVPrinter csv = new CSVPrinter(out, CSVFormat.DEFAULT.builder().setHeader(head.toArray(String[]::new)).get())) {
            for (Contact c : contacts.all(new Filter(q, status, assignedToId, unassignedOnly, importBatchId, branchId))) {
                Map<String, String> values = fields.read(c);
                List<String> row = new ArrayList<>(List.of(c.getFullName(), c.getPhone(), nz(c.getAltPhone()),
                        nz(c.getEmail()), c.getStatus().name(), nz(c.getSource()),
                        c.getAssignedTo() == null ? "" : c.getAssignedTo().getFullName(), nz(c.getNotes())));
                defs.forEach(d -> row.add(values.getOrDefault(d.getFieldKey(), "")));
                csv.printRecord(row);
            }
        }
        // A byte-order mark so Excel opens the file as UTF-8 (names and ₹ signs stay intact).
        byte[] body = ("﻿" + out).getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"contacts-" + LocalDate.now() + ".csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(body);
    }

    @GetMapping("/contacts/{id}")
    public ContactView get(@PathVariable Long id) {
        return contacts.get(id);
    }

    @PostMapping("/contacts")
    @ResponseStatus(HttpStatus.CREATED)
    public ContactView create(@Valid @RequestBody ContactRequest req) {
        return contacts.create(req);
    }

    @PutMapping("/contacts/{id}")
    public ContactView update(@PathVariable Long id, @Valid @RequestBody ContactRequest req) {
        return contacts.update(id, req);
    }

    @PostMapping("/contacts/{id}/status")
    public ContactView status(@PathVariable Long id, @Valid @RequestBody StatusRequest req) {
        return contacts.changeStatus(id, req);
    }

    @PostMapping("/contacts/{id}/assign")
    public ContactView assign(@PathVariable Long id, @RequestBody AssignRequest req) {
        return contacts.assign(id, req);
    }

    @GetMapping("/contacts/{id}/activities")
    public List<ActivityView> activities(@PathVariable Long id) {
        return contacts.activities(id);
    }

    @PostMapping("/contacts/{id}/activities")
    @ResponseStatus(HttpStatus.CREATED)
    public ActivityView addActivity(@PathVariable Long id, @Valid @RequestBody ActivityRequest req) {
        return contacts.addActivity(id, req);
    }

    @GetMapping("/contacts/{id}/follow-ups")
    public List<FollowUpView> followUps(@PathVariable Long id) {
        return contacts.followUps(id);
    }

    @PostMapping("/contacts/{id}/follow-ups")
    @ResponseStatus(HttpStatus.CREATED)
    public FollowUpView addFollowUp(@PathVariable Long id, @Valid @RequestBody FollowUpRequest req) {
        return contacts.addFollowUp(id, req);
    }

    @PostMapping("/follow-ups/{id}/complete")
    public FollowUpView completeFollowUp(@PathVariable Long id) {
        return contacts.completeFollowUp(id);
    }

    @GetMapping("/follow-ups/mine")
    public List<FollowUpView> myFollowUps(@RequestParam(defaultValue = "7") int days) {
        return contacts.myFollowUps(days);
    }

    // ------------------------------------------------------------------ custom fields

    @GetMapping("/fields")
    public List<FieldView> fields() {
        return fields.all();
    }

    @PostMapping("/fields")
    @ResponseStatus(HttpStatus.CREATED)
    public FieldView createField(@Valid @RequestBody FieldRequest req) {
        return fields.create(req);
    }

    @PutMapping("/fields/{id}")
    public FieldView updateField(@PathVariable Long id, @Valid @RequestBody FieldRequest req) {
        return fields.update(id, req);
    }

    // ------------------------------------------------------------------ imports

    @PostMapping(value = "/contacts/import/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Preview preview(@RequestPart("file") MultipartFile file) {
        return imports.preview(file);
    }

    @PostMapping(value = "/contacts/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Result runImport(@RequestPart("file") MultipartFile file, @RequestPart("options") Options options) {
        return imports.run(file, options);
    }

    @GetMapping("/contacts/imports")
    public List<BatchView> importHistory() {
        return imports.history();
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
