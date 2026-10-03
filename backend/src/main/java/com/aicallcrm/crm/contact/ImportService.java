package com.aicallcrm.crm.contact;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ActivityType;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.FieldDefinition.Type;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.user.AppUser;
import com.aicallcrm.crm.user.AppUserRepository;

import tools.jackson.databind.ObjectMapper;

/**
 * Bringing contacts in from the customer's own files (Excel .xlsx/.xls or CSV). Two steps, both from the same
 * upload: a preview that reads the headings and suggests what each column is, then the import itself with the
 * columns as the person confirmed them. Any column that is not a standard one becomes (or fills) a custom field,
 * so a spreadsheet with "Customer ID", "Plan" and "Amount due" needs no setting up beforehand.
 * A phone number already in the CRM updates that contact instead of creating a duplicate.
 */
@Service
public class ImportService {

    public static final int MAX_ROWS = 20_000;
    static final int MAX_PROBLEMS = 300;

    /** Standard columns a heading can be mapped to; anything else is "field:key", "new:TYPE" or "ignore". */
    public static final List<String> STANDARD = List.of("name", "phone", "alt_phone", "email", "notes", "source", "tags");

    public record Preview(String fileName, List<String> headers, List<List<String>> sample, int totalRows,
                          Map<String, String> suggested) {
    }

    /**
     * {@code mapping}: heading -> target. {@code consent}: these people agreed to AI calls (with proof).
     * {@code callScriptName}: when set, the AI agent starts calling everyone in this upload with that script.
     */
    public record Options(Map<String, String> mapping, boolean updateExisting, Long assignToId,
                          boolean aiCallConsent, boolean recordingConsent, String consentEvidence,
                          String callScriptName) {
    }

    /** {@code calls} is null unless AI calls were asked for. */
    public record Result(Long batchId, int totalRows, int created, int updated, int skipped, List<String> problems,
                         List<String> newFields, CallsStarted calls) {
    }

    /**
     * The calling campaign started for an upload. {@code callingNow}: can be called straight away;
     * {@code waiting}: will be called later (outside calling hours, say); {@code notAllowed}: will not be called
     * (no consent to AI calls, do-not-disturb...).
     */
    public record CallsStarted(Long campaignId, String campaignName, int people, int callingNow, int waiting,
                               int notAllowed) {
    }

    public record BatchView(Long id, String fileName, int totalRows, int created, int updated, int skipped,
                            Instant createdAt) {
        static BatchView of(ImportBatch b) {
            return new BatchView(b.getId(), b.getFileName(), b.getRowsTotal(), b.getCreatedCount(),
                    b.getUpdatedCount(), b.getSkippedCount(), b.getCreatedAt());
        }
    }

    /** Lets the voice module record consent for imported contacts without this module depending on it. */
    public interface ConsentRecorder {
        void recordFromImport(Contact contact, boolean recording, String evidence, Long userId);
    }

    /** Lets the voice module start AI calls to an upload without this module depending on it. */
    public interface CallStarter {
        /** Refuses (400/403) when calls cannot be made with this script, before anything is imported. */
        void check(String scriptName);

        CallsStarted start(Long batchId, String fileName, String scriptName);
    }

    private record Sheetful(List<String> headers, List<List<String>> rows) {
    }

    private final ContactRepository contacts;
    private final ContactActivityRepository activities;
    private final ImportBatchRepository batches;
    private final FieldService fields;
    private final AppUserRepository users;
    private final AuditService audit;
    private final ObjectMapper mapper;
    private final List<ConsentRecorder> consentRecorders;
    private final List<CallStarter> callStarters;
    private final TagService tags;

    public ImportService(ContactRepository contacts, ContactActivityRepository activities,
                         ImportBatchRepository batches, FieldService fields, AppUserRepository users,
                         AuditService audit, ObjectMapper mapper, List<ConsentRecorder> consentRecorders,
                         List<CallStarter> callStarters, TagService tags) {
        this.tags = tags;
        this.callStarters = callStarters;
        this.contacts = contacts;
        this.activities = activities;
        this.batches = batches;
        this.fields = fields;
        this.users = users;
        this.audit = audit;
        this.mapper = mapper;
        this.consentRecorders = consentRecorders;
    }

    // ------------------------------------------------------------------ step 1: preview

    @Transactional(readOnly = true)
    public Preview preview(MultipartFile file) {
        requireImporter();
        Sheetful s = read(file);
        Map<String, FieldDefinition> existing = new HashMap<>();
        fields.definitions().forEach(f -> existing.put(f.getFieldKey(), f));
        Map<String, String> suggested = new LinkedHashMap<>();
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < s.headers().size(); i++) {
            String h = s.headers().get(i);
            String guess = guess(h, column(s.rows(), i), existing);
            if (STANDARD.contains(guess) && !taken.add(guess)) {
                guess = "new:" + guessType(h, column(s.rows(), i));
            }
            suggested.put(h, guess);
        }
        return new Preview(file.getOriginalFilename(), s.headers(), s.rows().stream().limit(10).toList(),
                s.rows().size(), suggested);
    }

    /** Best guess at what a column holds, from its heading and, for new fields, from its values. */
    public static String guess(String header, List<String> values, Map<String, FieldDefinition> existing) {
        String h = header.toLowerCase(Locale.ROOT);
        String key = FieldService.keyOf(header);
        if (existing.containsKey(key)) {
            return "field:" + key;
        }
        if (h.matches(".*(alt|alternate|second|other|secondary).*(phone|mobile|number|no).*")) {
            return "alt_phone";
        }
        if (h.matches(".*(phone|mobile|cell|contact no|contact number|whatsapp|tel).*") || h.equals("number")) {
            return "phone";
        }
        if (h.matches(".*e-?mail.*")) {
            return "email";
        }
        if (h.matches("(full )?name|customer name|client name|contact name|customer|client|patient name|student name|lead name")) {
            return "name";
        }
        if (h.matches(".*(note|remark|comment).*")) {
            return "notes";
        }
        if (h.matches("source|lead source|channel")) {
            return "source";
        }
        if (h.matches("tags?|labels?|segments?|groups?")) {
            return "tags";
        }
        return "new:" + guessType(header, values);
    }

    public static Type guessType(String header, List<String> values) {
        String h = header.toLowerCase(Locale.ROOT);
        List<String> filled = values.stream().filter(v -> v != null && !v.isBlank()).limit(50).toList();
        if (filled.isEmpty()) {
            return Type.TEXT;
        }
        if (filled.stream().allMatch(ImportService::isDate) && !h.matches(".*(id|code|no|number|pin|zip).*")) {
            return Type.DATE;
        }
        if (filled.stream().allMatch(ImportService::isNumber) && !h.matches(".*(id|code|no\\b|number|pin|zip|phone|account).*")) {
            return h.matches(".*(amount|due|fee|fees|balance|price|cost|total|paid|emi|outstanding|rs|inr|₹).*")
                    ? Type.AMOUNT : Type.NUMBER;
        }
        return Type.TEXT;
    }

    private static boolean isDate(String v) {
        try {
            FieldService.parseDate(v.trim());
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static boolean isNumber(String v) {
        try {
            FieldService.normalise(Type.NUMBER, v);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    // ------------------------------------------------------------------ step 2: import

    @Transactional
    public Result run(MultipartFile file, Options opts) {
        CurrentUser me = requireImporter();
        Sheetful s = read(file);
        Map<String, String> mapping = opts.mapping() == null ? Map.of() : opts.mapping();
        if (!mapping.containsValue("name") || !mapping.containsValue("phone")) {
            throw ApiException.badRequest("Choose which column holds the name and which holds the phone number");
        }
        AppUser assignee = opts.assignToId() == null ? null : users.findById(opts.assignToId())
                .filter(AppUser::isActive).orElseThrow(() -> ApiException.badRequest("Staff member not found"));
        if (opts.aiCallConsent() && (opts.consentEvidence() == null || opts.consentEvidence().isBlank())) {
            throw ApiException.badRequest("Say how these people agreed to AI calls (for example the form they signed)");
        }
        String callScript = opts.callScriptName() == null || opts.callScriptName().isBlank() ? null
                : opts.callScriptName().trim();
        if (callScript != null) {
            // Calling campaigns are the admin's decision; check everything before a single row is imported.
            if (!me.isAdmin()) {
                throw ApiException.forbidden("Only the admin can start AI calls");
            }
            if (callStarters.isEmpty()) {
                throw ApiException.badRequest("AI calls are not available");
            }
            callStarters.get(0).check(callScript);
        }

        // Work out, once, what each column becomes. New fields are created now.
        Map<Integer, String> standard = new HashMap<>();
        Map<Integer, FieldDefinition> custom = new HashMap<>();
        List<String> newFields = new ArrayList<>();
        Map<String, FieldDefinition> byKey = new HashMap<>();
        fields.definitions().forEach(f -> byKey.put(f.getFieldKey(), f));
        for (int i = 0; i < s.headers().size(); i++) {
            String target = mapping.getOrDefault(s.headers().get(i), "ignore");
            if (STANDARD.contains(target)) {
                if (standard.containsValue(target)) {
                    throw ApiException.badRequest("Two columns are both set to \"" + target + "\"");
                }
                standard.put(i, target);
            } else if (target.startsWith("field:")) {
                FieldDefinition f = byKey.get(target.substring(6));
                if (f == null) {
                    throw ApiException.badRequest("Unknown field for column \"" + s.headers().get(i) + "\"");
                }
                custom.put(i, f);
            } else if (target.startsWith("new:")) {
                Type type;
                try {
                    type = Type.valueOf(target.substring(4));
                } catch (IllegalArgumentException e) {
                    throw ApiException.badRequest("Unknown field type for column \"" + s.headers().get(i) + "\"");
                }
                boolean existed = byKey.containsKey(FieldService.keyOf(s.headers().get(i)));
                FieldDefinition f = fields.createEntity(s.headers().get(i), type);
                byKey.put(f.getFieldKey(), f);
                custom.put(i, f);
                if (!existed) {
                    newFields.add(f.getLabel());
                }
            } else if (!"ignore".equals(target)) {
                throw ApiException.badRequest("Unknown choice for column \"" + s.headers().get(i) + "\"");
            }
        }

        Map<String, String> tagSpellings = tags.spellings();
        ImportBatch batch = batches.save(new ImportBatch(cut(file.getOriginalFilename(), 200), me.id()));
        List<String> problems = new ArrayList<>();
        int created = 0, updated = 0, skipped = 0;
        Set<String> seen = new HashSet<>();
        for (int r = 0; r < s.rows().size(); r++) {
            List<String> row = s.rows().get(r);
            int line = r + 2; // the heading is line 1, as the customer sees it in Excel
            if (row.stream().allMatch(v -> v == null || v.isBlank())) {
                continue;
            }
            Map<String, String> std = new HashMap<>();
            standard.forEach((i, t) -> std.put(t, cell(row, i)));
            String name = std.get("name");
            String phoneRaw = std.get("phone");
            String phone;
            try {
                if (name == null || name.isBlank()) {
                    throw ApiException.badRequest("no name");
                }
                phone = ContactService.phone(phoneRaw == null ? "" : phoneRaw);
            } catch (ApiException e) {
                skipped++;
                problem(problems, line, name == null || name.isBlank() ? "no name"
                        : phoneRaw == null || phoneRaw.isBlank() ? "no phone number"
                        : "phone \"" + phoneRaw + "\" is not a valid number");
                continue;
            }
            if (!seen.add(phone)) {
                skipped++;
                problem(problems, line, "same phone number as an earlier row in this file");
                continue;
            }
            Map<String, String> values = new LinkedHashMap<>();
            List<String> rowProblems = new ArrayList<>();
            custom.forEach((i, f) -> {
                String v = cell(row, i);
                if (v == null || v.isBlank()) {
                    return;
                }
                try {
                    values.put(f.getFieldKey(), FieldService.normalise(f.getFieldType(), v));
                } catch (IllegalArgumentException e) {
                    rowProblems.add(f.getLabel() + " " + e.getMessage() + " (left empty)");
                }
            });
            Contact existing = contacts.findByPhone(phone).orElse(null);
            if (existing != null && !opts.updateExisting()) {
                skipped++;
                problem(problems, line, "already in the CRM (" + existing.getFullName() + "), not changed");
                continue;
            }
            Contact c = existing != null ? existing : new Contact();
            c.setFullName(cut(name.trim(), 120));
            c.setPhone(phone);
            String alt = std.get("alt_phone");
            if (alt != null && !alt.isBlank()) {
                try {
                    c.setAltPhone(ContactService.phone(alt));
                } catch (ApiException e) {
                    rowProblems.add("alternate phone \"" + alt + "\" is not valid (left empty)");
                }
            }
            String email = std.get("email");
            if (email != null && !email.isBlank()) {
                if (email.trim().matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
                    c.setEmail(cut(email.trim(), 160));
                } else {
                    rowProblems.add("email \"" + email + "\" is not valid (left empty)");
                }
            }
            if (std.get("notes") != null && !std.get("notes").isBlank()) {
                c.setNotes(cut(std.get("notes").trim(), 2000));
            }
            c.setSource(std.get("source") != null && !std.get("source").isBlank() ? cut(std.get("source").trim(), 60)
                    : c.getSource() != null ? c.getSource() : cut("Import: " + nz(file.getOriginalFilename()), 60));
            if (assignee != null) {
                c.setAssignedTo(assignee);
            }
            if (std.get("tags") != null && !std.get("tags").isBlank()) {
                try {
                    TagService.add(c, TagService.split(std.get("tags")), tagSpellings);
                } catch (ApiException e) {
                    rowProblems.add(e.getMessage() + " (some tags left out)");
                }
            }
            Map<String, String> merged = existing == null ? new LinkedHashMap<>() : fields.read(c);
            merged.putAll(values);
            c.setFields(merged.isEmpty() ? null : mapper.writeValueAsString(merged));
            c.setImportBatchId(batch.getId());
            if (existing == null) {
                c.setCreatedBy(me.id());
                contacts.save(c);
                created++;
            } else {
                updated++;
            }
            activities.save(new ContactActivity(c.getId(), ActivityType.IMPORT,
                    (existing == null ? "Added from " : "Updated from ") + nz(file.getOriginalFilename()), null,
                    users.getReferenceById(me.id())));
            if (opts.aiCallConsent()) {
                for (ConsentRecorder rec : consentRecorders) {
                    rec.recordFromImport(c, opts.recordingConsent(), "Import " + nz(file.getOriginalFilename()) + ": "
                            + opts.consentEvidence().trim(), me.id());
                }
            }
            for (String p : rowProblems) {
                problem(problems, line, p);
            }
        }
        batch.result(s.rows().size(), created, updated, skipped, mapper.writeValueAsString(problems),
                cut(mapper.writeValueAsString(mapping), 4000));
        audit.record(me.id(), "CONTACTS_IMPORTED", "IMPORT_BATCH", batch.getId(),
                "created=" + created + " updated=" + updated + " skipped=" + skipped);
        CallsStarted calls = callScript == null ? null
                : created + updated == 0 ? new CallsStarted(null, null, 0, 0, 0, 0)
                : callStarters.get(0).start(batch.getId(), nz(file.getOriginalFilename()), callScript);
        return new Result(batch.getId(), s.rows().size(), created, updated, skipped, problems, newFields, calls);
    }

    @Transactional(readOnly = true)
    public List<BatchView> history() {
        requireImporter();
        return batches.findTop50ByOrderByCreatedAtDesc().stream().map(BatchView::of).toList();
    }

    // ------------------------------------------------------------------ reading files

    private Sheetful read(MultipartFile file) {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (file.isEmpty()) {
            throw ApiException.badRequest("The file is empty");
        }
        try (InputStream in = file.getInputStream()) {
            Sheetful s = name.endsWith(".csv") || name.endsWith(".txt") ? readCsv(in) : readExcel(in);
            if (s.headers().isEmpty()) {
                throw ApiException.badRequest("The first row must hold the column headings");
            }
            if (s.rows().size() > MAX_ROWS) {
                throw ApiException.badRequest("At most " + MAX_ROWS + " rows can be imported at a time; split the file");
            }
            return s;
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException api) {
                throw api;
            }
            throw ApiException.badRequest("This file could not be read. Upload an Excel file (.xlsx or .xls) or a CSV file.");
        }
    }

    private static Sheetful readCsv(InputStream in) throws IOException {
        Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
        try (CSVParser parser = CSVFormat.DEFAULT.builder().setIgnoreEmptyLines(true).setTrim(true).get().parse(reader)) {
            List<List<String>> all = new ArrayList<>();
            for (CSVRecord rec : parser) {
                List<String> row = new ArrayList<>();
                rec.forEach(row::add);
                all.add(row);
                if (all.size() > MAX_ROWS + 1) {
                    break;
                }
            }
            if (all.isEmpty()) {
                return new Sheetful(List.of(), List.of());
            }
            List<String> headers = headers(all.get(0));
            return new Sheetful(headers, all.subList(1, all.size()));
        }
    }

    /** The first sheet. Dates come out as yyyy-MM-dd and numbers as they are shown in Excel. */
    private static Sheetful readExcel(InputStream in) throws IOException {
        try (Workbook wb = WorkbookFactory.create(in)) {
            Sheet sheet = wb.getSheetAt(0);
            DataFormatter fmt = new DataFormatter(Locale.ENGLISH);
            List<List<String>> all = new ArrayList<>();
            int width = 0;
            for (Row row : sheet) {
                List<String> cells = new ArrayList<>();
                int last = Math.max(row.getLastCellNum(), 0);
                for (int i = 0; i < last; i++) {
                    cells.add(text(row.getCell(i), fmt));
                }
                if (all.isEmpty()) {
                    width = cells.size();
                }
                all.add(cells);
                if (all.size() > MAX_ROWS + 1) {
                    break;
                }
            }
            if (all.isEmpty() || width == 0) {
                return new Sheetful(List.of(), List.of());
            }
            return new Sheetful(headers(all.get(0)), all.subList(1, all.size()));
        }
    }

    private static String text(Cell cell, DataFormatter fmt) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        if (type == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getDateCellValue().toInstant().atZone(ZoneId.systemDefault()).toLocalDate().toString();
        }
        if (type == CellType.NUMERIC) {
            // Phone numbers stored as numbers must not turn into 9.87654321E9.
            double d = cell.getNumericCellValue();
            if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                return String.valueOf((long) d);
            }
        }
        return fmt.formatCellValue(cell).trim();
    }

    /** Blank headings get a name, and repeated ones a number, so every column can be told apart. */
    private static List<String> headers(List<String> raw) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < raw.size(); i++) {
            String h = raw.get(i) == null || raw.get(i).isBlank() ? "Column " + (i + 1) : raw.get(i).trim();
            String unique = h;
            for (int n = 2; !seen.add(unique.toLowerCase(Locale.ROOT)); n++) {
                unique = h + " (" + n + ")";
            }
            out.add(unique);
        }
        while (!out.isEmpty() && out.get(out.size() - 1).startsWith("Column ")
                && raw.size() >= out.size() && (raw.get(out.size() - 1) == null || raw.get(out.size() - 1).isBlank())) {
            out.remove(out.size() - 1);
        }
        return out;
    }

    private static List<String> column(List<List<String>> rows, int i) {
        return rows.stream().limit(200).map(r -> cell(r, i)).toList();
    }

    private static String cell(List<String> row, int i) {
        return i < row.size() ? row.get(i) : null;
    }

    private static void problem(List<String> problems, int line, String text) {
        if (problems.size() < MAX_PROBLEMS) {
            problems.add("Row " + line + ": " + text);
        }
    }

    private CurrentUser requireImporter() {
        CurrentUser me = CurrentUser.get();
        if (!me.seesAllContacts()) {
            throw ApiException.forbidden("Only an admin or a manager can import contacts");
        }
        return me;
    }

    private static String cut(String s, int max) {
        return s == null || s.length() <= max ? s : s.substring(0, max);
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
