package com.mbbscrm.crm.marketing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.branch.BranchRef;
import com.mbbscrm.crm.branch.BranchRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.LeadSource;
import com.mbbscrm.crm.security.CurrentUser;

import jakarta.persistence.EntityManager;
import jakarta.persistence.TypedQuery;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Campaigns, spend and the cost-per-lead / cost-per-admission report (spec 4.12). A lead counts as an
 * admission once its status is "Admission confirmed". Commission owed to referral associates is counted as
 * the cost of the associate channel, so it sits beside ad and seminar spend.
 */
@Service
public class MarketingService {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");

    private final CampaignRepository campaigns;
    private final CampaignSpendRepository spends;
    private final BranchRepository branches;
    private final EntityManager em;
    private final AuditService audit;

    public MarketingService(CampaignRepository campaigns, CampaignSpendRepository spends, BranchRepository branches,
                            EntityManager em, AuditService audit) {
        this.campaigns = campaigns;
        this.spends = spends;
        this.branches = branches;
        this.em = em;
        this.audit = audit;
    }

    // ------------------------------------------------------------------ DTOs

    public record CampaignRequest(
            @NotBlank @Size(max = 120) String name,
            @NotNull LeadSource channel,
            Long branchId,
            LocalDate startDate,
            LocalDate endDate,
            @DecimalMin("0") BigDecimal budget,
            @Size(max = 1000) String notes,
            boolean active) {
    }

    public record CampaignView(Long id, String name, LeadSource channel, BranchRef branch, LocalDate startDate,
                               LocalDate endDate, BigDecimal budget, String notes, boolean active, BigDecimal spent,
                               long leads, long converted, long admissions, BigDecimal costPerLead,
                               BigDecimal costPerAdmission) {
    }

    public record SpendRequest(@NotNull LocalDate spentOn, @NotNull @DecimalMin("0.01") BigDecimal amount,
                               @Size(max = 300) String note) {
    }

    public record SpendView(Long id, LocalDate spentOn, BigDecimal amount, String note) {
        static SpendView of(CampaignSpend s) {
            return new SpendView(s.getId(), s.getSpentOn(), s.getAmount(), s.getNote());
        }
    }

    public record ChannelRow(LeadSource channel, long leads, long converted, long admissions, BigDecimal campaignSpend,
                             BigDecimal commissions, BigDecimal totalCost, BigDecimal costPerLead,
                             BigDecimal costPerAdmission) {
    }

    public record Report(LocalDate from, LocalDate to, List<ChannelRow> channels, List<CampaignView> campaigns,
                         long totalLeads, long totalAdmissions, BigDecimal totalCost) {
    }

    /** Lead counts for one group: total, converted to student, admission confirmed. */
    private record Counts(long leads, long converted, long admissions) {
        static final Counts ZERO = new Counts(0, 0, 0);
    }

    // ------------------------------------------------------------------ campaigns

    /** Active campaigns a lead can be tagged with; branch staff see their branch's and the shared ones. */
    @Transactional(readOnly = true)
    public List<CampaignRef> options() {
        Long scope = CurrentUser.get().branchScope();
        return campaigns.findByActiveTrueOrderByName().stream()
                .filter(c -> scope == null || c.getBranch() == null || scope.equals(c.getBranch().getId()))
                .map(CampaignRef::of).toList();
    }

    @Transactional
    public CampaignView create(CampaignRequest req) {
        Campaign c = new Campaign();
        c.setCreatedBy(CurrentUser.get().id());
        apply(c, req);
        campaigns.save(c);
        audit.record(CurrentUser.get().id(), "CAMPAIGN_CREATED", "CAMPAIGN", c.getId(), c.getName());
        return view(c, BigDecimal.ZERO, Counts.ZERO);
    }

    @Transactional
    public CampaignView update(Long id, CampaignRequest req) {
        Campaign c = campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign"));
        apply(c, req);
        audit.record(CurrentUser.get().id(), "CAMPAIGN_UPDATED", "CAMPAIGN", c.getId(), "active=" + c.isActive());
        return report(null, null, null).campaigns().stream().filter(v -> v.id().equals(id)).findFirst()
                .orElseGet(() -> view(c, BigDecimal.ZERO, Counts.ZERO));
    }

    private void apply(Campaign c, CampaignRequest req) {
        if (req.startDate() != null && req.endDate() != null && req.endDate().isBefore(req.startDate())) {
            throw ApiException.badRequest("The end date cannot be before the start date");
        }
        c.setName(req.name().trim());
        c.setChannel(req.channel());
        c.setBranch(req.branchId() == null ? null : branches.findById(req.branchId())
                .orElseThrow(() -> ApiException.badRequest("Branch not found")));
        c.setStartDate(req.startDate());
        c.setEndDate(req.endDate());
        c.setBudget(req.budget());
        c.setNotes(req.notes() == null || req.notes().isBlank() ? null : req.notes().trim());
        c.setActive(req.active());
    }

    @Transactional(readOnly = true)
    public List<SpendView> spendFor(Long campaignId) {
        return spends.findByCampaignIdOrderBySpentOnDesc(campaignId).stream().map(SpendView::of).toList();
    }

    @Transactional
    public SpendView addSpend(Long campaignId, SpendRequest req) {
        Campaign c = campaigns.findById(campaignId).orElseThrow(() -> ApiException.notFound("Campaign"));
        if (req.spentOn().isAfter(LocalDate.now(IST))) {
            throw ApiException.badRequest("Spend cannot be dated in the future");
        }
        CurrentUser me = CurrentUser.get();
        CampaignSpend s = spends.save(new CampaignSpend(c, req.spentOn(), req.amount(),
                req.note() == null || req.note().isBlank() ? null : req.note().trim(), me.id()));
        audit.record(me.id(), "CAMPAIGN_SPEND_ADDED", "CAMPAIGN", c.getId(), req.amount() + " on " + req.spentOn());
        return SpendView.of(s);
    }

    @Transactional
    public void deleteSpend(Long spendId) {
        CampaignSpend s = spends.findById(spendId).orElseThrow(() -> ApiException.notFound("Spend entry"));
        audit.record(CurrentUser.get().id(), "CAMPAIGN_SPEND_DELETED", "CAMPAIGN", s.getCampaign().getId(),
                s.getAmount() + " on " + s.getSpentOn());
        spends.delete(s);
    }

    // ------------------------------------------------------------------ report

    /**
     * Leads created in the period (default: the last 12 months), grouped by channel and by campaign, against
     * money spent in the same period.
     */
    @Transactional(readOnly = true)
    public Report report(LocalDate from, LocalDate to, Long branchId) {
        LocalDate end = to == null ? LocalDate.now(IST) : to;
        LocalDate start = from == null ? end.minusYears(1) : from;
        if (start.isAfter(end)) {
            throw ApiException.badRequest("The start date must be before the end date");
        }
        Instant since = start.atStartOfDay(IST).toInstant();
        Instant until = end.plusDays(1).atStartOfDay(IST).toInstant();
        String branchClause = branchId == null ? "" : " and l.branch.id = :branchId";

        Map<LeadSource, Counts> bySource = new EnumMap<>(LeadSource.class);
        for (Object[] r : leadCounts("l.source", "", branchClause, since, until, branchId)) {
            bySource.put((LeadSource) r[0], counts(r));
        }
        Map<Long, Counts> byCampaign = new HashMap<>();
        for (Object[] r : leadCounts("l.campaign.id", " and l.campaign is not null", branchClause, since, until,
                branchId)) {
            byCampaign.put((Long) r[0], counts(r));
        }
        Map<Long, BigDecimal> spendByCampaign = new HashMap<>();
        for (Object[] r : spends.sumByCampaign(start, end)) {
            spendByCampaign.put((Long) r[0], (BigDecimal) r[1]);
        }

        List<CampaignView> campaignViews = new ArrayList<>();
        Map<LeadSource, BigDecimal> spendByChannel = new EnumMap<>(LeadSource.class);
        for (Campaign c : campaigns.findAllByOrderByCreatedAtDesc()) {
            if (branchId != null && c.getBranch() != null && !branchId.equals(c.getBranch().getId())) {
                continue;
            }
            BigDecimal spent = spendByCampaign.getOrDefault(c.getId(), BigDecimal.ZERO);
            spendByChannel.merge(c.getChannel(), spent, BigDecimal::add);
            campaignViews.add(view(c, spent, byCampaign.getOrDefault(c.getId(), Counts.ZERO)));
        }

        BigDecimal commissions = commissionTotal(since, until, branchId);
        List<ChannelRow> channels = new ArrayList<>();
        long totalLeads = 0;
        long totalAdmissions = 0;
        BigDecimal totalCost = BigDecimal.ZERO;
        for (LeadSource source : LeadSource.values()) {
            Counts c = bySource.getOrDefault(source, Counts.ZERO);
            BigDecimal spend = spendByChannel.getOrDefault(source, BigDecimal.ZERO);
            BigDecimal commission = source == LeadSource.REFERRAL_ASSOCIATE ? commissions : BigDecimal.ZERO;
            if (c.leads() == 0 && spend.signum() == 0 && commission.signum() == 0) {
                continue;
            }
            BigDecimal cost = spend.add(commission);
            channels.add(new ChannelRow(source, c.leads(), c.converted(), c.admissions(), spend, commission, cost,
                    per(cost, c.leads()), per(cost, c.admissions())));
            totalLeads += c.leads();
            totalAdmissions += c.admissions();
            totalCost = totalCost.add(cost);
        }
        return new Report(start, end, channels, campaignViews, totalLeads, totalAdmissions, totalCost);
    }

    private List<Object[]> leadCounts(String groupBy, String extra, String branchClause, Instant since, Instant until,
                                      Long branchId) {
        TypedQuery<Object[]> q = em.createQuery("select " + groupBy + ", count(l), "
                + "sum(case when l.student is not null then 1 else 0 end), "
                + "sum(case when l.status = com.mbbscrm.crm.common.LeadStatus.ADMISSION_CONFIRMED then 1 else 0 end) "
                + "from Lead l where l.createdAt >= :since and l.createdAt < :until" + extra + branchClause
                + " group by " + groupBy, Object[].class);
        q.setParameter("since", since).setParameter("until", until);
        if (branchId != null) {
            q.setParameter("branchId", branchId);
        }
        return q.getResultList();
    }

    private BigDecimal commissionTotal(Instant since, Instant until, Long branchId) {
        TypedQuery<BigDecimal> q = em.createQuery("select coalesce(sum(c.amount), 0) from CommissionEntry c "
                + "where c.status <> com.mbbscrm.crm.fee.CommissionStatus.CANCELLED "
                + "and c.createdAt >= :since and c.createdAt < :until"
                + (branchId == null ? "" : " and c.lead.branch.id = :branchId"), BigDecimal.class);
        q.setParameter("since", since).setParameter("until", until);
        if (branchId != null) {
            q.setParameter("branchId", branchId);
        }
        return q.getSingleResult();
    }

    private static Counts counts(Object[] r) {
        return new Counts(((Number) r[1]).longValue(), r[2] == null ? 0 : ((Number) r[2]).longValue(),
                r[3] == null ? 0 : ((Number) r[3]).longValue());
    }

    private static CampaignView view(Campaign c, BigDecimal spent, Counts n) {
        return new CampaignView(c.getId(), c.getName(), c.getChannel(), BranchRef.of(c.getBranch()), c.getStartDate(),
                c.getEndDate(), c.getBudget(), c.getNotes(), c.isActive(), spent, n.leads(), n.converted(),
                n.admissions(), per(spent, n.leads()), per(spent, n.admissions()));
    }

    /** Cost per unit, or null when there is nothing to divide by. */
    static BigDecimal per(BigDecimal cost, long units) {
        return units == 0 ? null : cost.divide(BigDecimal.valueOf(units), 0, RoundingMode.HALF_UP);
    }
}
