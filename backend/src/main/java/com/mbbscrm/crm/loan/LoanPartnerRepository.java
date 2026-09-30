package com.mbbscrm.crm.loan;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface LoanPartnerRepository extends JpaRepository<LoanPartner, Long> {

    List<LoanPartner> findAllByOrderByName();
}
