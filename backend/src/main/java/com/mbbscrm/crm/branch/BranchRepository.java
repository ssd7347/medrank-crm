package com.mbbscrm.crm.branch;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface BranchRepository extends JpaRepository<Branch, Long> {

    List<Branch> findAllByOrderByName();

    boolean existsByCodeIgnoreCase(String code);
}
