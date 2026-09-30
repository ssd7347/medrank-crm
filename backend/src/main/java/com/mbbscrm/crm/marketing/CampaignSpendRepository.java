package com.mbbscrm.crm.marketing;

import java.time.LocalDate;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CampaignSpendRepository extends JpaRepository<CampaignSpend, Long> {

    List<CampaignSpend> findByCampaignIdOrderBySpentOnDesc(Long campaignId);

    @Query("""
            select s.campaign.id, sum(s.amount) from CampaignSpend s
            where s.spentOn >= :from and s.spentOn <= :to group by s.campaign.id
            """)
    List<Object[]> sumByCampaign(LocalDate from, LocalDate to);
}
