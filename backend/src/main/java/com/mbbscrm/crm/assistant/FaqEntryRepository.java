package com.mbbscrm.crm.assistant;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mbbscrm.crm.common.Language;

public interface FaqEntryRepository extends JpaRepository<FaqEntry, Long> {

    List<FaqEntry> findAllByOrderByLanguageAscSortOrderAscIdAsc();

    List<FaqEntry> findByLanguageAndActiveTrueOrderBySortOrderAscIdAsc(Language language);
}
