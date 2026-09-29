package com.mbbscrm.crm.student;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Nationality;
import com.mbbscrm.crm.common.Quota;

/**
 * First-pass quota eligibility flags (spec 4.2). These are simplified, general rules to guide the counsellor;
 * each state and each year publishes its own fine print, so the result always carries a disclaimer and the
 * counsellor confirms against the official notification.
 */
@Service
public class EligibilityService {

    public static final String DISCLAIMER =
            "Indicative only. Confirm against the current MCC / state counselling notification.";

    public record QuotaFlag(Quota quota, boolean eligible, String reason) {
    }

    public record Eligibility(List<QuotaFlag> quotas, List<String> warnings, String disclaimer) {
    }

    public record Input(boolean neetQualified, Nationality nationality, boolean nriSponsored, String homeState,
                        DomicileStatus domicileStatus, Category category, boolean pwd,
                        LocalDate categoryCertValidUntil) {

        static Input of(Student s) {
            return new Input(s.isNeetQualified(), s.getNationality(), s.isNriSponsored(), s.getHomeState(),
                    s.getDomicileStatus(), s.getCategory(), s.isPwd(), s.getCategoryCertValidUntil());
        }
    }

    public Eligibility evaluate(Student s) {
        return evaluate(Input.of(s), LocalDate.now());
    }

    public Eligibility evaluate(Input in, LocalDate today) {
        List<QuotaFlag> flags = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (!in.neetQualified()) {
            for (Quota q : Quota.values()) {
                flags.add(new QuotaFlag(q, false, "Not NEET-qualified"));
            }
            return new Eligibility(flags, List.of("Candidate has not qualified NEET; no quota is available."),
                    DISCLAIMER);
        }

        Nationality nat = in.nationality() == null ? Nationality.INDIAN : in.nationality();
        boolean indian = nat == Nationality.INDIAN;

        flags.add(indian
                ? new QuotaFlag(Quota.AIQ, true, "Indian national, NEET-qualified")
                : new QuotaFlag(Quota.AIQ, false, "AIQ government seats are for Indian nationals"));

        if (!indian) {
            flags.add(new QuotaFlag(Quota.STATE, false, "State quota requires Indian nationality and domicile"));
        } else if (in.domicileStatus() == DomicileStatus.DOMICILED) {
            flags.add(new QuotaFlag(Quota.STATE, true, "Domiciled in " + nvl(in.homeState(), "home state")));
        } else if (in.domicileStatus() == DomicileStatus.UNKNOWN || in.domicileStatus() == null) {
            flags.add(new QuotaFlag(Quota.STATE, false, "Domicile not yet confirmed"));
            warnings.add("Confirm domicile to decide home-state quota eligibility.");
        } else {
            flags.add(new QuotaFlag(Quota.STATE, false,
                    "Not domiciled in home state; some states allow non-domicile private seats"));
        }

        flags.add(new QuotaFlag(Quota.DEEMED, true, "Deemed universities are open to all NEET-qualified candidates"));
        flags.add(new QuotaFlag(Quota.MANAGEMENT, true, "Open to NEET-qualified candidates, subject to state rules"));

        boolean nriEligible = nat == Nationality.NRI || nat == Nationality.OCI || in.nriSponsored();
        flags.add(new QuotaFlag(Quota.NRI, nriEligible, nriEligible
                ? (in.nriSponsored() && indian ? "Sponsored by an NRI relative (documents required)" : "NRI/OCI status")
                : "Requires NRI/OCI status or an NRI sponsor"));
        if (nat == Nationality.FOREIGN) {
            warnings.add("Foreign nationals are usually limited to NRI/foreign-national seats; check each authority.");
        }

        flags.add(new QuotaFlag(Quota.MINORITY, false, "Depends on the college's minority community rules"));

        if (in.category() != null && in.category() != Category.GEN) {
            if (in.categoryCertValidUntil() == null) {
                warnings.add(in.category() + " certificate validity date not recorded.");
            } else if (in.categoryCertValidUntil().isBefore(today)) {
                warnings.add(in.category() + " certificate expired on " + in.categoryCertValidUntil()
                        + "; reservation benefit is at risk until it is renewed.");
            } else if (in.categoryCertValidUntil().isBefore(today.plusDays(60))) {
                warnings.add(in.category() + " certificate expires on " + in.categoryCertValidUntil()
                        + "; plan renewal before reporting.");
            }
        }
        if (in.pwd()) {
            warnings.add("PwD candidate: must hold a disability certificate from a designated centre.");
        }
        return new Eligibility(flags, warnings, DISCLAIMER);
    }

    private static String nvl(String s, String fallback) {
        return s == null || s.isBlank() ? fallback : s;
    }
}
