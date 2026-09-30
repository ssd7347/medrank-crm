package com.mbbscrm.crm.portal;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface PortalAccountRepository extends JpaRepository<PortalAccount, Long> {

    Optional<PortalAccount> findByPhone(String phone);
}
