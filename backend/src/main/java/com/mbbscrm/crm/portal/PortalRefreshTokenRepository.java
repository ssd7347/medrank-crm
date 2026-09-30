package com.mbbscrm.crm.portal;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface PortalRefreshTokenRepository extends JpaRepository<PortalRefreshToken, Long> {

    Optional<PortalRefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update PortalRefreshToken t set t.revoked = true where t.accountId = :accountId and t.revoked = false")
    void revokeAllForAccount(Long accountId);
}
