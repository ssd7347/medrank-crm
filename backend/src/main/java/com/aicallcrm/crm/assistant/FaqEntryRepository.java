package com.aicallcrm.crm.assistant;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;


public interface FaqEntryRepository extends JpaRepository<FaqEntry, Long> {

    List<FaqEntry> findAllByOrderBySortOrderAscIdAsc();

    List<FaqEntry> findByActiveTrueOrderBySortOrderAscIdAsc();
}
