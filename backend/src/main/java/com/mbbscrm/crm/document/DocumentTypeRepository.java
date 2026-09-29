package com.mbbscrm.crm.document;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentTypeRepository extends JpaRepository<DocumentType, Long> {

    List<DocumentType> findAllByOrderBySortOrderAscNameAsc();

    List<DocumentType> findByActiveTrueOrderBySortOrderAscNameAsc();

    boolean existsByCodeIgnoreCase(String code);
}
