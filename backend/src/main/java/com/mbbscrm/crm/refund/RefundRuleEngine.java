package com.mbbscrm.crm.refund;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.common.CounsellingRound;

/**
 * Finds the rule that governs giving up a particular seat (spec 4.27) and states exactly what is at stake.
 * Matching: same academic year; a rule for the specific college beats one for the authority + round, which
 * beats one for the whole authority. Within the winning scope, time tiers pick the first bound not yet passed.
 */
@Service
public class RefundRuleEngine {

    private final RefundRuleRepository rules;

    public RefundRuleEngine(RefundRuleRepository rules) {
        this.rules = rules;
    }

    public record Seat(int academicYear, Long authorityId, Long collegeId, CounsellingRound roundType,
                       Instant allottedAt) {
    }

    public record Assessment(boolean ruleFound, Long ruleId, boolean depositForfeited, BigDecimal depositAmount,
                             BigDecimal tuitionRefundPercent, boolean barredFromLaterRounds, String source,
                             List<String> lines) {
    }

    @Transactional(readOnly = true)
    public Assessment assess(Seat seat, Instant asOf) {
        return assess(rules.findByAcademicYear(seat.academicYear()), seat, asOf);
    }

    /** Pure matching logic, unit-tested separately. */
    static Assessment assess(List<RefundRule> candidates, Seat seat, Instant asOf) {
        long days = Math.max(0, ChronoUnit.DAYS.between(seat.allottedAt(), asOf));
        List<RefundRule> matching = candidates.stream().filter(r -> matches(r, seat)).toList();
        if (matching.isEmpty()) {
            return new Assessment(false, null, false, null, null, false, null, List.of(
                    "No withdrawal rule is recorded for this seat yet. Check the official notification before "
                            + "advising, and ask the data team to add the rule."));
        }
        int best = matching.stream().mapToInt(RefundRuleEngine::specificity).max().orElseThrow();
        RefundRule rule = matching.stream()
                .filter(r -> specificity(r) == best)
                .filter(r -> r.getMaxDaysAfterAllotment() == null || days <= r.getMaxDaysAfterAllotment())
                .min(Comparator.comparing(r -> r.getMaxDaysAfterAllotment() == null ? Integer.MAX_VALUE
                        : r.getMaxDaysAfterAllotment()))
                .orElse(null);
        if (rule == null) {
            return new Assessment(false, null, false, null, null, false, null, List.of(
                    "The recorded tiers for this seat end before day " + days + " after allotment. Check the "
                            + "notification before advising."));
        }
        List<String> lines = new ArrayList<>();
        if (rule.isDepositForfeited()) {
            lines.add("Security deposit forfeited" + (rule.getDepositAmount() == null ? "."
                    : ": Rs " + rule.getDepositAmount().toPlainString() + " will be lost."));
        } else {
            lines.add("Security deposit is refundable" + (rule.getDepositAmount() == null ? "."
                    : " (Rs " + rule.getDepositAmount().toPlainString() + ")."));
        }
        BigDecimal pct = rule.getTuitionRefundPercent();
        lines.add(pct.compareTo(BigDecimal.valueOf(100)) == 0 ? "Tuition fee paid is refunded in full."
                : pct.signum() == 0 ? "No refund of tuition fee paid."
                : pct.stripTrailingZeros().toPlainString() + "% of the tuition fee paid is refunded.");
        if (rule.isBarredFromLaterRounds()) {
            lines.add("The student is barred from later rounds of this counselling.");
        }
        if (rule.getMaxDaysAfterAllotment() != null) {
            lines.add("Applies up to day " + rule.getMaxDaysAfterAllotment() + " after allotment (today is day "
                    + days + ").");
        }
        if (rule.getNotes() != null) {
            lines.add(rule.getNotes());
        }
        lines.add("Source: " + rule.getSource());
        return new Assessment(true, rule.getId(), rule.isDepositForfeited(), rule.getDepositAmount(), pct,
                rule.isBarredFromLaterRounds(), rule.getSource(), lines);
    }

    private static boolean matches(RefundRule r, Seat s) {
        return r.getAcademicYear() == s.academicYear()
                && (r.getCollegeId() == null || Objects.equals(r.getCollegeId(), s.collegeId()))
                && (r.getAuthorityId() == null || Objects.equals(r.getAuthorityId(), s.authorityId()))
                && (r.getRoundType() == null || r.getRoundType() == s.roundType());
    }

    private static int specificity(RefundRule r) {
        return (r.getCollegeId() != null ? 4 : 0) + (r.getAuthorityId() != null ? 2 : 0) + (r.getRoundType() != null ? 1 : 0);
    }
}
