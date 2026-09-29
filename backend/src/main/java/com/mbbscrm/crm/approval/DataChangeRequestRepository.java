package com.mbbscrm.crm.approval;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DataChangeRequestRepository extends JpaRepository<DataChangeRequest, Long> {

    @EntityGraph(attributePaths = {"requestedBy", "reviewedBy"})
    Page<DataChangeRequest> findByStatus(DataChangeRequest.Status status, Pageable pageable);

    @EntityGraph(attributePaths = {"requestedBy", "reviewedBy"})
    Page<DataChangeRequest> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = {"requestedBy", "reviewedBy"})
    List<DataChangeRequest> findByEntityTypeAndEntityIdOrderByRequestedAtDesc(DataChangeRequest.EntityType type,
                                                                              Long entityId);

    long countByStatus(DataChangeRequest.Status status);
}
