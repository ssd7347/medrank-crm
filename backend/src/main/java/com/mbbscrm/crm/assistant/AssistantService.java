package com.mbbscrm.crm.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.alert.AlertService;
import com.mbbscrm.crm.alert.Priority;
import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ActivityType;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.common.Phones;
import com.mbbscrm.crm.common.Role;
import com.mbbscrm.crm.lead.Lead;
import com.mbbscrm.crm.lead.LeadActivity;
import com.mbbscrm.crm.lead.LeadActivityRepository;
import com.mbbscrm.crm.lead.LeadRepository;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.user.AppUser;
import com.mbbscrm.crm.user.AppUserRepository;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Website assistant (spec 4.17): answers common questions from the FAQ knowledge base in English, Tamil or
 * Hindi, collects the visitor's NEET details, and hands them to a counsellor as a lead with the
 * conversation attached.
 *
 * Answers come only from the FAQ entries staff have written, matched by keywords. No AI model is connected,
 * so it never invents an answer: when nothing matches it says so and offers a call back. An LLM can later
 * replace {@link #answer} without changing the rest.
 */
@Service
public class AssistantService {

    private final FaqEntryRepository faqs;
    private final LeadRepository leads;
    private final LeadActivityRepository activities;
    private final AppUserRepository users;
    private final AlertService alerts;
    private final AuditService audit;

    public AssistantService(FaqEntryRepository faqs, LeadRepository leads, LeadActivityRepository activities,
                            AppUserRepository users, AlertService alerts, AuditService audit) {
        this.faqs = faqs;
        this.leads = leads;
        this.activities = activities;
        this.users = users;
        this.alerts = alerts;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record FaqRequest(@NotNull Language language, @NotBlank @Size(max = 300) String question,
                             @NotBlank @Size(max = 2000) String answer, @Size(max = 500) String keywords,
                             @Min(0) @Max(10_000) int sortOrder, boolean active) {
    }

    public record FaqView(Long id, Language language, String question, String answer, String keywords,
                          int sortOrder, boolean active) {
        static FaqView of(FaqEntry f) {
            return new FaqView(f.getId(), f.getLanguage(), f.getQuestion(), f.getAnswer(), f.getKeywords(),
                    f.getSortOrder(), f.isActive());
        }
    }

    public record PublicFaq(Long id, String question, String answer) {
        static PublicFaq of(FaqEntry f) {
            return new PublicFaq(f.getId(), f.getQuestion(), f.getAnswer());
        }
    }

    /** {@code answer} is null when nothing in the knowledge base matched. */
    public record Answer(PublicFaq answer, List<PublicFaq> related) {
    }

    public record CallbackRequest(
            @NotBlank @Size(max = 120) String fullName,
            @NotBlank @Pattern(regexp = "^[0-9+ -]{10,20}$", message = "invalid phone") String phone,
            @Min(-180) @Max(720) Integer neetScore,
            @Min(1) @Max(3_000_000) Integer neetAir,
            Category category,
            @Size(max = 40) String homeState,
            Language language,
            /** What the visitor asked, so the counsellor has the context. */
            @Size(max = 10) List<@Size(max = 300) String> questions,
            /** Hidden from people; only bots fill it in. */
            @Size(max = 200) String website) {
    }

    // ------------------------------------------------------------------ knowledge base (staff)

    @Transactional(readOnly = true)
    public List<FaqView> all() {
        return faqs.findAllByOrderByLanguageAscSortOrderAscIdAsc().stream().map(FaqView::of).toList();
    }

    @Transactional
    public FaqView save(Long id, FaqRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can change the knowledge base");
        }
        FaqEntry f = id == null ? new FaqEntry() : faqs.findById(id).orElseThrow(() -> ApiException.notFound("FAQ"));
        f.setLanguage(req.language());
        f.setQuestion(req.question().trim());
        f.setAnswer(req.answer().trim());
        f.setKeywords(req.keywords() == null || req.keywords().isBlank() ? null : req.keywords().trim());
        f.setSortOrder(req.sortOrder());
        f.setActive(req.active());
        f.setUpdatedBy(me.id());
        faqs.save(f);
        audit.record(me.id(), id == null ? "FAQ_CREATED" : "FAQ_UPDATED", "FAQ", f.getId(), f.getLanguage().name());
        return FaqView.of(f);
    }

    // ------------------------------------------------------------------ public

    @Transactional(readOnly = true)
    public List<PublicFaq> topQuestions(Language language) {
        return faqs.findByLanguageAndActiveTrueOrderBySortOrderAscIdAsc(language).stream().limit(8)
                .map(PublicFaq::of).toList();
    }

    @Transactional(readOnly = true)
    public Answer answer(Language language, String question) {
        String q = normalise(question);
        record Scored(FaqEntry entry, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (FaqEntry f : faqs.findByLanguageAndActiveTrueOrderBySortOrderAscIdAsc(language)) {
            int s = score(f, q);
            if (s > 0) {
                scored.add(new Scored(f, s));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        PublicFaq best = scored.isEmpty() ? null : PublicFaq.of(scored.get(0).entry());
        List<PublicFaq> related = scored.stream().skip(1).limit(2).map(s -> PublicFaq.of(s.entry())).toList();
        return new Answer(best, related);
    }

    /** 3 points per keyword or phrase found in the question, 1 per shared word of four letters or more. */
    static int score(FaqEntry f, String normalisedQuestion) {
        int score = 0;
        if (f.getKeywords() != null) {
            for (String k : f.getKeywords().split(",")) {
                String key = normalise(k);
                if (!key.isEmpty() && normalisedQuestion.contains(key)) {
                    score += 3;
                }
            }
        }
        String faqQuestion = " " + normalise(f.getQuestion()) + " ";
        for (String word : normalisedQuestion.split(" ")) {
            if (word.length() >= 4 && faqQuestion.contains(" " + word + " ")) {
                score += 1;
            }
        }
        return score;
    }

    static String normalise(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\p{Punct}\\s]+", " ").trim();
    }

    /**
     * Turns the visitor into a lead for a counsellor to call. If the phone number is already a lead, the
     * new questions are added to that lead instead. The reply never reveals which of the two happened.
     */
    @Transactional
    public void requestCallback(CallbackRequest req) {
        String phone = Phones.normalize(req.phone());
        if (phone == null || phone.length() != 10) {
            throw ApiException.badRequest("Please enter a 10-digit mobile number");
        }
        StringBuilder context = new StringBuilder("From the website assistant.");
        if (req.questions() != null && !req.questions().isEmpty()) {
            context.append(" They asked:");
            req.questions().stream().filter(q -> q != null && !q.isBlank())
                    .forEach(q -> context.append("\n- ").append(q.trim()));
        }
        String notes = context.length() > 2000 ? context.substring(0, 2000) : context.toString();

        List<Lead> existing = leads.findByAnyPhone(phone);
        Lead lead;
        if (!existing.isEmpty()) {
            lead = existing.get(0);
            activities.save(new LeadActivity(lead.getId(), ActivityType.NOTE, "Came back through the website assistant",
                    notes, null));
        } else {
            lead = new Lead();
            lead.setFullName(req.fullName().trim());
            lead.setPhone(phone);
            lead.setNeetScore(req.neetScore());
            lead.setNeetAir(req.neetAir());
            lead.setCategory(req.category());
            lead.setHomeState(req.homeState() == null || req.homeState().isBlank() ? null : req.homeState().trim());
            lead.setSource(LeadSource.WEBSITE_CHAT);
            lead.setLanguagePreference(req.language() == null ? Language.ENGLISH : req.language());
            lead.setNotes(notes);
            leads.save(lead);
            audit.record(null, "LEAD_CREATED", "LEAD", lead.getId(), "source=WEBSITE_CHAT");
        }
        // Response time is the top cause of lost leads (spec 4.17): tell the owner, or every admin, at once.
        AppUser owner = lead.getAssignedCounsellor();
        List<Long> recipients = owner != null && owner.isActive() ? List.of(owner.getId())
                : users.findByActiveTrueAndRoleInOrderByFullName(List.of(Role.SUPER_ADMIN)).stream()
                        .map(AppUser::getId).toList();
        for (Long recipient : recipients) {
            alerts.notifyUser(recipient, null, "WEBSITE_LEAD", Priority.NORMAL,
                    "Website enquiry: " + lead.getFullName(), "Asked for a call back. " + (owner == null
                            ? "Not assigned to anyone yet." : ""), "/leads/" + lead.getId(), null);
        }
    }
}
