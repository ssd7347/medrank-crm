package com.mbbscrm.crm.agreement;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AgreementTemplateRepository extends JpaRepository<AgreementTemplate, Long> {

    List<AgreementTemplate> findAllByOrderByKindAscTitleAsc();
}
