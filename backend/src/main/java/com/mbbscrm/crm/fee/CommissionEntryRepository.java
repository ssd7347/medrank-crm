package com.mbbscrm.crm.fee;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CommissionEntryRepository extends JpaRepository<CommissionEntry, Long> {

    boolean existsByAssociateIdAndLeadId(Long associateId, Long leadId);

    @EntityGraph(attributePaths = {"associate", "lead", "student"})
    List<CommissionEntry> findAllByOrderByCreatedAtDesc();

    @EntityGraph(attributePaths = {"associate", "lead", "student"})
    List<CommissionEntry> findByStatusOrderByCreatedAtDesc(CommissionStatus status);
}
