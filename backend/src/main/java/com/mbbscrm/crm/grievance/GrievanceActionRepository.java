package com.mbbscrm.crm.grievance;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GrievanceActionRepository extends JpaRepository<GrievanceAction, Long> {

    @EntityGraph(attributePaths = "actor")
    List<GrievanceAction> findByGrievanceIdOrderByCreatedAtAsc(Long grievanceId);
}
