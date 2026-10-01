package com.aicallcrm.crm.contact;

import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.FieldDefinition.AgentAccess;
import com.aicallcrm.crm.contact.FieldDefinition.Type;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Custom fields: the extra columns each customer keeps about their contacts. Values are stored as text in a
 * normal form (dates as yyyy-MM-dd, amounts and numbers as plain decimals) so they can be compared, filtered
 * and read out the same way whatever the spreadsheet looked like.
 */
@Service
public class FieldService {

    public static final int MAX_FIELDS = 60;

    public record FieldRequest(@NotBlank @Size(max = 80) String label, @NotNull Type fieldType,
                               @NotNull AgentAccess agentAccess, boolean verifyWith,
                               @Min(0) @Max(10_000) int sortOrder, boolean active) {
    }

    public record FieldView(Long id, String key, String label, Type fieldType, AgentAccess agentAccess,
                            boolean verifyWith, int sortOrder, boolean active) {
        static FieldView of(FieldDefinition f) {
            return new FieldView(f.getId(), f.getFieldKey(), f.getLabel(), f.getFieldType(), f.getAgentAccess(),
                    f.isVerifyWith(), f.getSortOrder(), f.isActive());
        }
    }

    private static final List<DateTimeFormatter> DATE_FORMATS = List.of(
            DateTimeFormatter.ISO_LOCAL_DATE,
            DateTimeFormatter.ofPattern("d/M/uuuu"), DateTimeFormatter.ofPattern("d-M-uuuu"),
            DateTimeFormatter.ofPattern("d.M.uuuu"), DateTimeFormatter.ofPattern("d MMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d-MMM-uuuu", Locale.ENGLISH), DateTimeFormatter.ofPattern("d MMMM uuuu", Locale.ENGLISH),
            DateTimeFormatter.ofPattern("d/M/uu"), DateTimeFormatter.ofPattern("uuuu/M/d"));
    private static final DateTimeFormatter SPOKEN_DATE = DateTimeFormatter.ofPattern("EEEE d MMMM uuuu", Locale.ENGLISH);

    private final FieldDefinitionRepository fields;
    private final ObjectMapper mapper;
    private final AuditService audit;

    public FieldService(FieldDefinitionRepository fields, ObjectMapper mapper, AuditService audit) {
        this.fields = fields;
        this.mapper = mapper;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ definitions

    @Transactional(readOnly = true)
    public List<FieldView> all() {
        return fields.findAllByOrderBySortOrderAscIdAsc().stream().map(FieldView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<FieldDefinition> definitions() {
        return fields.findAllByOrderBySortOrderAscIdAsc();
    }

    @Transactional
    public FieldView create(FieldRequest req) {
        CurrentUser me = admin();
        FieldDefinition f = createEntity(req.label(), req.fieldType());
        apply(f, req);
        audit.record(me.id(), "FIELD_CREATED", "FIELD", f.getId(), f.getFieldKey());
        return FieldView.of(f);
    }

    @Transactional
    public FieldView update(Long id, FieldRequest req) {
        CurrentUser me = admin();
        FieldDefinition f = fields.findById(id).orElseThrow(() -> ApiException.notFound("Field"));
        apply(f, req);
        audit.record(me.id(), "FIELD_UPDATED", "FIELD", f.getId(), f.getFieldKey());
        return FieldView.of(f);
    }

    private void apply(FieldDefinition f, FieldRequest req) {
        f.setLabel(req.label().trim());
        f.setFieldType(req.fieldType());
        f.setAgentAccess(req.agentAccess());
        f.setVerifyWith(req.verifyWith());
        f.setSortOrder(req.sortOrder());
        f.setActive(req.active());
    }

    /** Used by the import to turn a new spreadsheet heading into a field. Reuses one with the same key. */
    @Transactional
    public FieldDefinition createEntity(String label, Type type) {
        String key = keyOf(label);
        if (key.isEmpty()) {
            throw ApiException.badRequest("A field needs a name with letters or digits in it");
        }
        Optional<FieldDefinition> existing = fields.findByFieldKey(key);
        if (existing.isPresent()) {
            return existing.get();
        }
        if (fields.count() >= MAX_FIELDS) {
            throw ApiException.badRequest("There can be at most " + MAX_FIELDS + " custom fields");
        }
        FieldDefinition f = new FieldDefinition(key, label.trim().length() > 80 ? label.trim().substring(0, 80)
                : label.trim(), type);
        f.setSortOrder((int) fields.count() * 10 + 100);
        return fields.save(f);
    }

    /** "Amount Due (₹)" becomes "amount_due". */
    public static String keyOf(String label) {
        String key = label == null ? "" : label.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return key.length() > 40 ? key.substring(0, 40) : key;
    }

    // ------------------------------------------------------------------ values

    /** The custom values of a contact, in field order. Never null. */
    public Map<String, String> read(Contact c) {
        if (c.getFields() == null || c.getFields().isBlank()) {
            return new LinkedHashMap<>();
        }
        try {
            return mapper.readValue(c.getFields(), new TypeReference<LinkedHashMap<String, String>>() { });
        } catch (JacksonException e) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * Checks and normalises every value against its field, then stores them. Unknown keys are refused; a blank
     * value removes the field from the contact. {@code merge} keeps the values not mentioned.
     */
    public void write(Contact c, Map<String, String> values, boolean merge) {
        Map<String, FieldDefinition> defs = new LinkedHashMap<>();
        definitions().forEach(d -> defs.put(d.getFieldKey(), d));
        Map<String, String> out = merge ? read(c) : new LinkedHashMap<>();
        if (values != null) {
            for (Map.Entry<String, String> e : values.entrySet()) {
                FieldDefinition def = defs.get(e.getKey());
                if (def == null) {
                    throw ApiException.badRequest("Unknown field: " + e.getKey());
                }
                String raw = e.getValue() == null ? "" : e.getValue().trim();
                if (raw.isEmpty()) {
                    out.remove(def.getFieldKey());
                } else {
                    try {
                        out.put(def.getFieldKey(), normalise(def.getFieldType(), raw));
                    } catch (IllegalArgumentException ex) {
                        throw ApiException.badRequest(def.getLabel() + ": " + ex.getMessage());
                    }
                }
            }
        }
        c.setFields(out.isEmpty() ? null : mapper.writeValueAsString(out));
    }

    /** Brings a value into its stored form, or explains why it cannot be read. */
    public static String normalise(Type type, String raw) {
        String v = raw.trim();
        return switch (type) {
            case TEXT -> v.length() > 500 ? v.substring(0, 500) : v;
            case NUMBER, AMOUNT -> {
                String digits = v.replaceAll("(?i)rs\\.?|inr|₹|,|\\s", "");
                try {
                    yield new BigDecimal(digits).stripTrailingZeros().toPlainString();
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("\"" + v + "\" is not a number");
                }
            }
            case DATE -> parseDate(v).toString();
        };
    }

    static LocalDate parseDate(String v) {
        for (DateTimeFormatter f : DATE_FORMATS) {
            try {
                return LocalDate.parse(v, f);
            } catch (DateTimeParseException ignored) {
                // try the next format
            }
        }
        // A date-and-time such as "2026-10-03 00:00:00" (how spreadsheets often export dates).
        if (v.matches("\\d{4}-\\d{2}-\\d{2}[ T].*")) {
            return LocalDate.parse(v.substring(0, 10));
        }
        throw new IllegalArgumentException("\"" + v + "\" is not a date (use e.g. 25/10/2026 or 2026-10-25)");
    }

    /** How the AI agent should say a stored value: amounts in rupees, dates with the weekday. */
    public static String spoken(FieldDefinition f, String value) {
        if (value == null) {
            return null;
        }
        try {
            return switch (f.getFieldType()) {
                case AMOUNT -> NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"))
                        .format(new BigDecimal(value)) + " rupees";
                case DATE -> SPOKEN_DATE.format(LocalDate.parse(value));
                default -> value;
            };
        } catch (RuntimeException e) {
            return value;
        }
    }

    private static CurrentUser admin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only the admin can change the custom fields");
        }
        return me;
    }
}
