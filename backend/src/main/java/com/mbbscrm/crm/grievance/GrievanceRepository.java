package com.mbbscrm.crm.grievance;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface GrievanceRepository extends JpaRepository<Grievance, Long> {

    @EntityGraph(attributePaths = {"student", "assignedOfficer", "createdBy"})
    @Query("select g from Grievance g where g.status in :statuses order by g.targetResolutionDate asc")
    List<Grievance> findByStatuses(Collection<GrievanceStatus> statuses);

    @EntityGraph(attributePaths = {"student", "assignedOfficer", "createdBy"})
    List<Grievance> findByCreatedByIdOrderByCreatedAtDesc(Long userId);

    @EntityGraph(attributePaths = {"student", "assignedOfficer"})
    @Query("""
            select g from Grievance g
            where g.status not in (com.mbbscrm.crm.grievance.GrievanceStatus.RESOLVED,
                                   com.mbbscrm.crm.grievance.GrievanceStatus.CLOSED)
              and g.targetResolutionDate < :today
            """)
    List<Grievance> findOverdue(LocalDate today);
}
