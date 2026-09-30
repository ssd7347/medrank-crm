package com.mbbscrm.crm.marketing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/** Money spent on a campaign on a given day. */
@Entity
@Table(name = "campaign_spend")
public class CampaignSpend {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "campaign_id")
    private Campaign campaign;

    private LocalDate spentOn;
    private BigDecimal amount;
    private String note;
    private Long createdBy;
    private Instant createdAt;

    protected CampaignSpend() {
    }

    public CampaignSpend(Campaign campaign, LocalDate spentOn, BigDecimal amount, String note, Long createdBy) {
        this.campaign = campaign;
        this.spentOn = spentOn;
        this.amount = amount;
        this.note = note;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Campaign getCampaign() { return campaign; }
    public LocalDate getSpentOn() { return spentOn; }
    public BigDecimal getAmount() { return amount; }
    public String getNote() { return note; }
}
