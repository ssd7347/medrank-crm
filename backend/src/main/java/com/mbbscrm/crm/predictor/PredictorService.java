package com.mbbscrm.crm.predictor;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.college.CollegeFee;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.predictor.PredictorDtos.Band;
import com.mbbscrm.crm.predictor.PredictorDtos.ClosingRank;
import com.mbbscrm.crm.predictor.PredictorDtos.PredictRequest;
import com.mbbscrm.crm.predictor.PredictorDtos.PredictResponse;
import com.mbbscrm.crm.predictor.PredictorDtos.Prediction;
import com.mbbscrm.crm.student.EligibilityService;
import com.mbbscrm.crm.student.Student;
import com.mbbscrm.crm.student.StudentService;

/**
 * Rank-based college predictor (spec 4.4). Compares a rank with the closing ranks recorded for the last
 * three counselling years:
 * <ul>
 *   <li>HIGH: rank is within every recent year's Round 1 closing rank (would have got in first round).</li>
 *   <li>MODERATE: rank is within the latest year's final closing rank (got in by the last round).</li>
 *   <li>LOW: rank is up to 10% beyond the latest final closing rank (possible if cutoffs move).</li>
 * </ul>
 * Everything else is left out. Closing ranks must be entered in the same rank terms the authority uses
 * (AIR for MCC, state merit rank for state quota), which is why the result always carries a disclaimer.
 */
@Service
public class PredictorService {

    public static final String DISCLAIMER = "Estimates only, based on previous years' closing ranks. Cutoffs change "
            + "every year with seats, applicants and rules; this is not a guarantee of admission.";
    static final int YEARS = 3;
    static final double LOW_MARGIN = 1.10;
    static final int MAX_RESULTS = 300;

    private final PredictorQueries queries;
    private final StudentService students;
    private final EligibilityService eligibility;

    public PredictorService(PredictorQueries queries, StudentService students, EligibilityService eligibility) {
        this.queries = queries;
        this.students = students;
        this.eligibility = eligibility;
    }

    private record Inputs(int rank, Category category, boolean pwd, String homeState, boolean domiciled,
                          Course course, Set<Quota> quotas) {
    }

    @Transactional(readOnly = true)
    public PredictResponse predict(PredictRequest req) {
        Inputs in = resolve(req);
        Integer latest = queries.latestYear(in.course());
        if (latest == null) {
            return new PredictResponse(in.rank(), in.category(), in.pwd(), in.course(), in.quotas(), List.of(),
                    List.of(), false, DISCLAIMER);
        }
        int fromYear = latest - YEARS + 1;
        List<CutoffRow> rows = queries.cutoffs(in.course(), in.category(), in.pwd(), in.quotas(),
                fromYear);

        // Group closing ranks by college + quota.
        Map<String, List<CutoffRow>> groups = new LinkedHashMap<>();
        for (CutoffRow r : rows) {
            College c = r.college();
            if (r.quota() == Quota.STATE && (!in.domiciled() || !c.getState().equals(in.homeState()))) {
                continue; // state quota is only for the student's own domicile state
            }
            if (req.states() != null && !req.states().isEmpty() && !req.states().contains(c.getState())) {
                continue;
            }
            if (req.collegeTypes() != null && !req.collegeTypes().isEmpty()
                    && !req.collegeTypes().contains(c.getCollegeType())) {
                continue;
            }
            groups.computeIfAbsent(c.getId() + "|" + r.quota(), k -> new ArrayList<>()).add(r);
        }

        Map<String, CollegeFee> fees = latestFees(queries.fees(in.course(),
                groups.values().stream().map(g -> g.get(0).college().getId()).distinct().toList()));

        List<Prediction> results = new ArrayList<>();
        List<Integer> years = new ArrayList<>();
        for (List<CutoffRow> g : groups.values()) {
            Prediction p = evaluate(in.rank(), in.course(), g, fees);
            if (p == null) {
                continue;
            }
            if (req.maxAnnualFee() != null && p.annualTuition() != null
                    && p.annualTuition().compareTo(req.maxAnnualFee()) > 0) {
                continue;
            }
            results.add(p);
            g.forEach(r -> {
                if (!years.contains(r.year())) {
                    years.add(r.year());
                }
            });
        }
        results.sort(Comparator.comparing(Prediction::band).thenComparingInt(Prediction::lastClosingRank));
        years.sort(Comparator.reverseOrder());
        boolean truncated = results.size() > MAX_RESULTS;
        return new PredictResponse(in.rank(), in.category(), in.pwd(), in.course(), in.quotas(), years,
                truncated ? results.subList(0, MAX_RESULTS) : results, truncated, DISCLAIMER);
    }

    /** Scores one college+quota. Package-private for unit tests. */
    static Prediction evaluate(int rank, Course course, List<CutoffRow> rows,
                               Map<String, CollegeFee> fees) {
        College c = rows.get(0).college();
        Quota quota = rows.get(0).quota();
        // year -> (round -> closing rank)
        TreeMap<Integer, Map<CounsellingRound, Integer>> byYear = new TreeMap<>();
        for (CutoffRow r : rows) {
            byYear.computeIfAbsent(r.year(), y -> new HashMap<>()).merge(r.round(), r.closingRank(), Math::max);
        }
        int latestYear = byYear.lastKey();
        int lastClosing = byYear.lastEntry().getValue().values().stream().mapToInt(Integer::intValue).max().orElseThrow();
        // "Safe" rank: the tightest of each year's Round 1 closing (or that year's final closing if no Round 1).
        int safe = byYear.values().stream()
                .mapToInt(m -> m.getOrDefault(CounsellingRound.ROUND_1,
                        m.values().stream().mapToInt(Integer::intValue).max().orElseThrow()))
                .min().orElseThrow();

        Band band;
        if (rank <= safe) {
            band = Band.HIGH;
        } else if (rank <= lastClosing) {
            band = Band.MODERATE;
        } else if (rank <= lastClosing * LOW_MARGIN) {
            band = Band.LOW;
        } else {
            return null;
        }
        List<ClosingRank> history = new ArrayList<>();
        byYear.descendingMap().forEach((y, m) -> m.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(e -> history.add(new ClosingRank(y, e.getKey(), e.getValue()))));
        CollegeFee fee = fees.get(c.getId() + "|" + quota);
        return new Prediction(c.getId(), c.getName(), c.getCode(), c.getState(), c.getCity(), c.getCollegeType(),
                quota, course, band, latestYear, lastClosing, history,
                fee == null ? null : fee.getAnnualTuition(), fee == null ? null : fee.getAcademicYear());
    }

    private Inputs resolve(PredictRequest req) {
        Course course = req.course() == null ? Course.MBBS : req.course();
        if (req.studentId() != null) {
            Student s = students.requireReadable(req.studentId());
            Integer rank = req.rank() != null ? req.rank() : s.getNeetAir();
            if (rank == null) {
                throw ApiException.badRequest("This student has no All India Rank yet; enter a rank to predict");
            }
            Set<Quota> eligible = EnumSet.noneOf(Quota.class);
            eligibility.evaluate(s).quotas().stream().filter(EligibilityService.QuotaFlag::eligible)
                    .forEach(f -> eligible.add(f.quota()));
            Set<Quota> quotas = req.quotas() == null || req.quotas().isEmpty() ? eligible : intersect(req.quotas(), eligible);
            return new Inputs(rank, s.getCategory(), s.isPwd(), s.getHomeState(),
                    s.getDomicileStatus() == DomicileStatus.DOMICILED, course, quotas);
        }
        if (req.rank() == null) {
            throw ApiException.badRequest("Enter a rank");
        }
        Set<Quota> quotas = req.quotas() == null || req.quotas().isEmpty()
                ? EnumSet.of(Quota.AIQ, Quota.STATE, Quota.DEEMED, Quota.MANAGEMENT) : EnumSet.copyOf(req.quotas());
        if (quotas.contains(Quota.STATE) && (req.homeState() == null || req.homeState().isBlank())) {
            quotas.remove(Quota.STATE);
        }
        return new Inputs(req.rank(), req.category() == null ? Category.GEN : req.category(),
                Boolean.TRUE.equals(req.pwd()), req.homeState(), !Boolean.FALSE.equals(req.domiciled()), course,
                quotas);
    }

    private static Set<Quota> intersect(Set<Quota> a, Set<Quota> b) {
        Set<Quota> out = EnumSet.noneOf(Quota.class);
        a.stream().filter(b::contains).forEach(out::add);
        return out;
    }

    private static Map<String, CollegeFee> latestFees(List<CollegeFee> list) {
        Map<String, CollegeFee> out = new HashMap<>();
        for (CollegeFee f : list) {
            out.merge(f.getCollegeId() + "|" + f.getQuota(), f,
                    (a, b) -> a.getAcademicYear() >= b.getAcademicYear() ? a : b);
        }
        return out;
    }

}
