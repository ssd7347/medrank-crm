package com.mbbscrm.crm.security;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;

public interface LoginOtpRepository extends JpaRepository<LoginOtp, Long> {

    Optional<LoginOtp> findFirstBySubjectTypeAndSubjectIdAndUsedFalseOrderByCreatedAtDesc(LoginOtp.Subject subjectType,
                                                                                          Long subjectId);

    @Modifying
    @Query("update LoginOtp o set o.used = true where o.subjectType = :type and o.subjectId = :id and o.used = false")
    void cancelAllFor(LoginOtp.Subject type, Long id);
}
