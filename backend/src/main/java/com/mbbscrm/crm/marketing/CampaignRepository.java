package com.mbbscrm.crm.marketing;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CampaignRepository extends JpaRepository<Campaign, Long> {

    @EntityGraph(attributePaths = "branch")
    List<Campaign> findAllByOrderByCreatedAtDesc();

    List<Campaign> findByActiveTrueOrderByName();
}
