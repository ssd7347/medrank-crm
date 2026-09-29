package com.mbbscrm.crm.referral;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface ReferralAssociateRepository extends JpaRepository<ReferralAssociate, Long> {

    List<ReferralAssociate> findAllByOrderByFullName();
}
