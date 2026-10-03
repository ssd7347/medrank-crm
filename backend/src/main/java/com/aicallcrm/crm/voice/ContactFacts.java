package com.aicallcrm.crm.voice;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactStatus;
import com.aicallcrm.crm.contact.FieldDefinition;
import com.aicallcrm.crm.contact.FieldDefinition.AgentAccess;
import com.aicallcrm.crm.contact.FieldService;
import com.aicallcrm.crm.voice.Voice.FieldOp;

/**
 * What the agent may know about a contact, and whether a contact belongs in a campaign. Both come straight
 * from the contact's record and its custom fields; nothing is estimated.
 */
@Service
public class ContactFacts {

    static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter SPOKEN_TIME = DateTimeFormatter.ofPattern("EEEE d MMMM, h:mm a", Locale.ENGLISH);

    /** "Thursday 1 October, 4:30 PM", Indian time. */
    static String spokenTime(Instant at) {
        return SPOKEN_TIME.format(at.atZone(IST));
    }

    /** One detail the agent may say, already worded for speech ("5,000 rupees", "Friday 3 October 2026"). */
    public record Fact(String key, String label, String spoken) {
    }

    private final FieldService fields;

    public ContactFacts(FieldService fields) {
        this.fields = fields;
    }

    /** ANYONE fields at level 0; AFTER_VERIFY fields too once the caller is verified. NONE fields never. */
    @Transactional(readOnly = true)
    public List<Fact> visible(Contact c, int verificationLevel) {
        Map<String, String> values = fields.read(c);
        List<Fact> out = new ArrayList<>();
        for (FieldDefinition f : fields.definitions()) {
            boolean allowed = f.isActive() && (f.getAgentAccess() == AgentAccess.ANYONE
                    || (f.getAgentAccess() == AgentAccess.AFTER_VERIFY && verificationLevel >= 1));
            String v = values.get(f.getFieldKey());
            if (allowed && v != null && !v.isBlank()) {
                out.add(new Fact(f.getFieldKey(), f.getLabel(), FieldService.spoken(f, v)));
            }
        }
        return out;
    }

    /** True if at least one detail is hidden until the caller proves who they are. */
    @Transactional(readOnly = true)
    public boolean hasPrivateDetails(Contact c) {
        Map<String, String> values = fields.read(c);
        return fields.definitions().stream().anyMatch(f -> f.isActive() && f.getAgentAccess() == AgentAccess.AFTER_VERIFY
                && values.get(f.getFieldKey()) != null);
    }

    /** The fields a caller can be asked to confirm, besides their name (e.g. a customer ID). */
    @Transactional(readOnly = true)
    public List<FieldDefinition> verificationFields() {
        return fields.definitions().stream().filter(f -> f.isActive() && f.isVerifyWith()).toList();
    }

    @Transactional(readOnly = true)
    public Map<String, String> values(Contact c) {
        return fields.read(c);
    }

    // ------------------------------------------------------------------ campaign audience

    /** Whether a contact is (still) someone this campaign should call. Do-not-call contacts never are. */
    @Transactional(readOnly = true)
    public boolean matches(VoiceCampaign campaign, Contact c) {
        if (ContactStatus.DO_NOT_CALL.equals(c.getStatus())) {
            return false;
        }
        Set<String> statuses = statuses(campaign.getFilterStatuses());
        if (!statuses.isEmpty() && !statuses.contains(c.getStatus())) {
            return false;
        }
        if (campaign.getFilterImportId() != null && !campaign.getFilterImportId().equals(c.getImportBatchId())) {
            return false;
        }
        if (campaign.getBranchId() != null && (c.getBranch() == null || !campaign.getBranchId().equals(c.getBranch().getId()))) {
            return false;
        }
        if (campaign.getFilterFieldKey() == null || campaign.getFilterFieldOp() == null) {
            return true;
        }
        return fieldMatches(campaign.getFilterFieldOp(), fields.read(c).get(campaign.getFilterFieldKey()),
                campaign.getFilterFieldValue(), LocalDate.now(IST));
    }

    static Set<String> statuses(String csv) {
        return csv == null || csv.isBlank() ? Set.of()
                : Arrays.stream(csv.split(",")).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    /** Pure rule, unit tested: does one stored field value satisfy the campaign's condition today? */
    static boolean fieldMatches(FieldOp op, String value, String filterValue, LocalDate today) {
        if (value == null || value.isBlank()) {
            return false;
        }
        try {
            return switch (op) {
                case NOT_EMPTY -> true;
                case EQUALS -> filterValue != null && value.trim().toLowerCase(Locale.ROOT)
                        .equals(filterValue.trim().toLowerCase(Locale.ROOT));
                case GREATER_THAN -> new BigDecimal(value).compareTo(new BigDecimal(
                        filterValue == null || filterValue.isBlank() ? "0" : filterValue.trim())) > 0;
                case OVERDUE -> LocalDate.parse(value).isBefore(today);
                case DUE_WITHIN_DAYS -> {
                    LocalDate due = LocalDate.parse(value);
                    int days = filterValue == null || filterValue.isBlank() ? 3 : Integer.parseInt(filterValue.trim());
                    yield !due.isBefore(today) && !due.isAfter(today.plusDays(days));
                }
            };
        } catch (RuntimeException e) {
            // A value of the wrong kind (text in a date field, say) simply does not match.
            return false;
        }
    }
}
