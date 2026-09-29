package com.mbbscrm.crm.college;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface CollegeRepository extends JpaRepository<College, Long>, JpaSpecificationExecutor<College> {

    Optional<College> findByCodeIgnoreCase(String code);

    List<College> findByCodeIn(Collection<String> codes);
}
