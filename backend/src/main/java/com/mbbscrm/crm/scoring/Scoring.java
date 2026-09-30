package com.mbbscrm.crm.scoring;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import com.mbbscrm.crm.common.LeadStatus;
import com.mbbscrm.crm.engagement.CallLog.Outcome;

/**
 * Lead conversion score and student drop-off risk (spec 4.18).
 *
 * These are transparent point rules, not a trained model: there is not yet enough history to train one, and
 * a counsellor should be able to see exactly why a lead is "hot" or a student is "at risk". Every score
 * comes with its reasons. Once a few seasons of outcomes exist, the rules can be replaced by a model behind
 * the same two methods.
 */
public final class Scoring {

    private Scoring() {
    }

    public enum Band {
        HOT, WARM, COLD
    }

    public enum Risk {
        HIGH, MEDIUM, LOW
    }

    /** What is known about a lead at scoring time. {@code recentCalls} is newest first, at most three. */
    public record LeadFacts(LeadStatus status, Instant createdAt, boolean profileComplete, Instant firstContactAt,
                            Instant lastContactAt, List<Outcome> recentCalls, Instant nextFollowUpDue,
                            Double sourceConversionRate, Double overallConversionRate) {
    }

    public record LeadScore(int score, Band band, List<String> reasons, String nextAction) {
    }

    public record StudentFacts(long overdueInstalmentDays, long openTickets, long overdueTickets, long openGrievances,
                               List<Outcome> recentCalls, long unacknowledgedUrgentAlerts) {
    }

    public record RiskScore(int score, Risk risk, List<String> reasons) {
    }

    public static LeadScore lead(LeadFacts f, Instant now) {
        int score = 30;
        List<String> reasons = new ArrayList<>();
        String next = null;

        if (f.status() == LeadStatus.FEE_PAID || f.status() == LeadStatus.ACTIVELY_COUNSELLED) {
            score += 25;
            reasons.add("Has already paid the fee");
        } else if (f.status() == LeadStatus.QUALIFIED) {
            score += 10;
            reasons.add("Qualified by a counsellor");
        }
        if (f.profileComplete()) {
            score += 10;
            reasons.add("Shared NEET score, category and state");
        }

        // Response speed: the first contact within a day is the strongest thing staff control.
        if (f.firstContactAt() == null) {
            long hours = Duration.between(f.createdAt(), now).toHours();
            if (hours >= 48) {
                score -= 15;
                reasons.add("Nobody has contacted them in " + (hours / 24) + " days");
            }
            next = "Not contacted yet: call now";
        } else if (Duration.between(f.createdAt(), f.firstContactAt()).toHours() <= 24) {
            score += 10;
            reasons.add("First contact was within a day");
        }

        // Engagement.
        List<Outcome> calls = f.recentCalls() == null ? List.of() : f.recentCalls();
        if (!calls.isEmpty() && calls.get(0) == Outcome.CONNECTED) {
            score += 15;
            reasons.add("Answered the last call");
        }
        if (!calls.isEmpty() && calls.get(0) == Outcome.CALL_BACK_LATER) {
            score += 5;
            next = "Asked to be called back";
        }
        if (calls.size() >= 3 && calls.stream().noneMatch(Outcome::reached)) {
            score -= 15;
            reasons.add("Last 3 calls did not get through");
            if (next == null) {
                next = "Try WhatsApp or the alternate number";
            }
        }
        if (calls.contains(Outcome.WRONG_NUMBER)) {
            score -= 20;
            reasons.add("A call reached a wrong number");
        }
        if (f.lastContactAt() != null) {
            long idleDays = Duration.between(f.lastContactAt(), now).toDays();
            if (idleDays <= 7) {
                score += 10;
                reasons.add("In touch within the last week");
            } else if (idleDays >= 21) {
                score -= 10;
                reasons.add("No contact for " + idleDays + " days");
                if (next == null) {
                    next = "Gone quiet: check in";
                }
            }
        }
        if (f.nextFollowUpDue() != null && f.nextFollowUpDue().isBefore(now)) {
            score -= 10;
            reasons.add("A follow-up is overdue");
            next = "Follow-up overdue";
        }

        // Past pattern: how this lead's source has converted compared with all sources.
        if (f.sourceConversionRate() != null && f.overallConversionRate() != null) {
            int delta = (int) Math.round((f.sourceConversionRate() - f.overallConversionRate()) * 100 / 2);
            delta = Math.max(-15, Math.min(15, delta));
            if (delta >= 5) {
                reasons.add("Leads from this source convert better than average");
            } else if (delta <= -5) {
                reasons.add("Leads from this source convert less often");
            }
            score += delta;
        }

        score = Math.max(0, Math.min(100, score));
        Band band = score >= 70 ? Band.HOT : score >= 40 ? Band.WARM : Band.COLD;
        return new LeadScore(score, band, reasons, next);
    }

    public static RiskScore student(StudentFacts f) {
        int score = 0;
        List<String> reasons = new ArrayList<>();
        if (f.overdueInstalmentDays() > 0) {
            score += f.overdueInstalmentDays() > 14 ? 35 : 25;
            reasons.add("Fee instalment overdue by " + f.overdueInstalmentDays() + " days");
        }
        if (f.openGrievances() > 0) {
            score += 25;
            reasons.add("Has an open grievance");
        }
        if (f.overdueTickets() > 0) {
            score += 20;
            reasons.add(f.overdueTickets() + (f.overdueTickets() == 1 ? " ticket" : " tickets") + " past the reply time");
        } else if (f.openTickets() > 0) {
            score += 5;
            reasons.add("Has an open ticket");
        }
        List<Outcome> calls = f.recentCalls() == null ? List.of() : f.recentCalls();
        if (calls.size() >= 3 && calls.stream().noneMatch(Outcome::reached)) {
            score += 20;
            reasons.add("Last 3 calls did not get through");
        } else if (!calls.isEmpty() && !calls.get(0).reached()) {
            score += 5;
            reasons.add("Missed the last call");
        }
        if (f.unacknowledgedUrgentAlerts() > 0) {
            score += 15;
            reasons.add("Has not confirmed an urgent alert");
        }
        score = Math.min(100, score);
        Risk risk = score >= 50 ? Risk.HIGH : score >= 25 ? Risk.MEDIUM : Risk.LOW;
        return new RiskScore(score, risk, reasons);
    }
}
