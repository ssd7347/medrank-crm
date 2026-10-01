package com.aicallcrm.crm.assistant;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * The knowledge base the AI agent answers general questions from (opening hours, prices, policies...). It is
 * matched by keywords, so the agent can only repeat what the customer wrote here and never invents an answer.
 */
@Service
public class AssistantService {

    public record FaqRequest(@NotBlank @Size(max = 300) String question, @NotBlank @Size(max = 2000) String answer,
                             @Size(max = 500) String keywords, @Min(0) @Max(10_000) int sortOrder, boolean active) {
    }

    public record FaqView(Long id, String question, String answer, String keywords, int sortOrder, boolean active) {
        static FaqView of(FaqEntry f) {
            return new FaqView(f.getId(), f.getQuestion(), f.getAnswer(), f.getKeywords(), f.getSortOrder(), f.isActive());
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

    private final FaqEntryRepository faqs;
    private final AuditService audit;

    public AssistantService(FaqEntryRepository faqs, AuditService audit) {
        this.faqs = faqs;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<FaqView> all() {
        return faqs.findAllByOrderBySortOrderAscIdAsc().stream().map(FaqView::of).toList();
    }

    @Transactional
    public FaqView save(Long id, FaqRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.seesAllContacts()) {
            throw ApiException.forbidden("Only an admin or a manager can change the knowledge base");
        }
        FaqEntry f = id == null ? new FaqEntry() : faqs.findById(id).orElseThrow(() -> ApiException.notFound("FAQ"));
        f.setQuestion(req.question().trim());
        f.setAnswer(req.answer().trim());
        f.setKeywords(req.keywords() == null || req.keywords().isBlank() ? null : req.keywords().trim());
        f.setSortOrder(req.sortOrder());
        f.setActive(req.active());
        f.setUpdatedBy(me.id());
        faqs.save(f);
        audit.record(me.id(), id == null ? "FAQ_CREATED" : "FAQ_UPDATED", "FAQ", f.getId(), null);
        return FaqView.of(f);
    }

    @Transactional(readOnly = true)
    public Answer answer(String question) {
        String q = normalise(question);
        record Scored(FaqEntry entry, int score) {
        }
        List<Scored> scored = new ArrayList<>();
        for (FaqEntry f : faqs.findByActiveTrueOrderBySortOrderAscIdAsc()) {
            int s = score(f, q);
            if (s > 0) {
                scored.add(new Scored(f, s));
            }
        }
        scored.sort(Comparator.comparingInt(Scored::score).reversed());
        PublicFaq best = scored.isEmpty() ? null : PublicFaq.of(scored.get(0).entry());
        return new Answer(best, scored.stream().skip(1).limit(2).map(s -> PublicFaq.of(s.entry())).toList());
    }

    /** 3 points per keyword or phrase found in the question, 1 per shared word of four letters or more. */
    static int score(FaqEntry f, String normalisedQuestion) {
        int score = 0;
        if (f.getKeywords() != null) {
            for (String k : f.getKeywords().split(",")) {
                String key = normalise(k);
                if (!key.isEmpty() && (" " + normalisedQuestion + " ").contains(" " + key + " ")) {
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
}
