package com.mbbscrm.crm.lead;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.lead.LeadDtos.ImportError;
import com.mbbscrm.crm.lead.LeadDtos.ImportResult;
import com.mbbscrm.crm.security.CurrentUser;

/**
 * Bulk lead import from CSV (spec 4.1). Header row required; recognised columns:
 * full_name, phone, email, neet_roll_no, neet_score, neet_air, category, home_state, source, notes.
 * Rows whose phone or roll number already exists are skipped, not overwritten.
 */
@Service
public class LeadImportService {

    static final int MAX_ROWS = 5000;
    private static final Pattern PHONE = Pattern.compile("^[0-9]{10}$");
    private static final Pattern ROLL = Pattern.compile("^[A-Z0-9]{1,20}$");

    private final LeadService leadService;
    private final AuditService audit;

    public LeadImportService(LeadService leadService, AuditService audit) {
        this.leadService = leadService;
        this.audit = audit;
    }

    public ImportResult importCsv(MultipartFile file) {
        CurrentUser me = LeadService.requireLeadAccess();
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a CSV file to import");
        }
        int imported = 0;
        int skipped = 0;
        List<ImportError> errors = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setIgnoreHeaderCase(true).get();
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            Map<String, Integer> header = parser.getHeaderMap();
            if (!header.containsKey("full_name") || !header.containsKey("phone")) {
                throw ApiException.badRequest("CSV must have at least 'full_name' and 'phone' columns");
            }
            for (CSVRecord rec : parser) {
                int row = (int) rec.getRecordNumber() + 1; // +1 for the header line
                if (rec.getRecordNumber() > MAX_ROWS) {
                    errors.add(new ImportError(row, "Stopped: at most " + MAX_ROWS + " rows per file"));
                    break;
                }
                try {
                    Lead lead = toLead(rec);
                    if (leadService.importOne(lead, me)) {
                        imported++;
                    } else {
                        skipped++;
                    }
                } catch (IllegalArgumentException e) {
                    errors.add(new ImportError(row, e.getMessage()));
                }
            }
        } catch (IOException | IllegalStateException e) {
            throw ApiException.badRequest("Could not read the CSV file. Save it as UTF-8 CSV and try again.");
        }
        audit.recordStandalone(me.id(), "LEADS_IMPORTED", "LEAD", null,
                "imported=" + imported + ", skipped=" + skipped + ", errors=" + errors.size());
        return new ImportResult(imported, skipped, errors);
    }

    private static Lead toLead(CSVRecord rec) {
        String name = get(rec, "full_name");
        if (name == null || name.length() > 120) {
            throw new IllegalArgumentException("full_name is required (max 120 characters)");
        }
        String phone = Phones.normalize(get(rec, "phone"));
        if (phone == null || !PHONE.matcher(phone).matches()) {
            throw new IllegalArgumentException("phone must be a 10-digit mobile number");
        }
        Lead lead = new Lead();
        lead.setFullName(name);
        lead.setPhone(phone);
        String email = get(rec, "email");
        if (email != null && (email.length() > 160 || !email.contains("@"))) {
            throw new IllegalArgumentException("email is not valid");
        }
        lead.setEmail(email);
        String roll = LeadService.normalizeRoll(get(rec, "neet_roll_no"));
        if (roll != null && !ROLL.matcher(roll).matches()) {
            throw new IllegalArgumentException("neet_roll_no must be letters and digits only");
        }
        lead.setNeetRollNo(roll);
        lead.setNeetScore(parseInt(get(rec, "neet_score"), "neet_score", -180, 720));
        lead.setNeetAir(parseInt(get(rec, "neet_air"), "neet_air", 1, Integer.MAX_VALUE));
        lead.setCategory(parseEnum(Category.class, get(rec, "category"), "category"));
        String state = get(rec, "home_state");
        if (state != null && state.length() > 40) {
            throw new IllegalArgumentException("home_state is too long");
        }
        lead.setHomeState(state);
        LeadSource source = parseEnum(LeadSource.class, get(rec, "source"), "source");
        if (source == LeadSource.REFERRAL_ASSOCIATE) {
            throw new IllegalArgumentException("referral leads must be added one by one so the associate is linked");
        }
        lead.setSource(source == null ? LeadSource.OTHER : source);
        String notes = get(rec, "notes");
        lead.setNotes(notes == null ? null : notes.substring(0, Math.min(notes.length(), 2000)));
        return lead;
    }

    private static String get(CSVRecord rec, String column) {
        if (!rec.isMapped(column) || !rec.isSet(column)) {
            return null;
        }
        String v = rec.get(column);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static Integer parseInt(String v, String column, int min, int max) {
        if (v == null) {
            return null;
        }
        try {
            int n = Integer.parseInt(v.replace(",", ""));
            if (n < min || n > max) {
                throw new IllegalArgumentException(column + " is out of range");
            }
            return n;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(column + " must be a whole number");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v, String column) {
        if (v == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(column + " '" + v + "' is not recognised");
        }
    }
}
