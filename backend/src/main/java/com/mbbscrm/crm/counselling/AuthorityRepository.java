package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mbbscrm.crm.common.CounsellingRound;

public interface AuthorityRepository extends JpaRepository<CounsellingAuthority, Long> {
    List<CounsellingAuthority> findAllByOrderByAuthorityTypeAscNameAsc();

    boolean existsByCodeIgnoreCase(String code);
}
