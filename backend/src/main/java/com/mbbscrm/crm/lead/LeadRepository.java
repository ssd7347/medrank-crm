package com.mbbscrm.crm.lead;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/**
 * "Scoped" queries return what a counsellor/telecaller can see: leads assigned to them plus unassigned ones.
 */
public interface LeadRepository extends JpaRepository<Lead, Long>, JpaSpecificationExecutor<Lead> {

    @Override
    @EntityGraph(attributePaths = {"assignedCounsellor", "referralAssociate"})
    Page<Lead> findAll(Specification<Lead> spec, Pageable pageable);

    @Query("select l from Lead l where l.phone = :phone or l.altPhone = :phone")
    List<Lead> findByAnyPhone(String phone);

    List<Lead> findByNeetRollNo(String neetRollNo);

    Optional<Lead> findFirstByStudentId(Long studentId);

    @Query("select l.status, count(l) from Lead l group by l.status")
    List<Object[]> countByStatus();

    @Query("""
            select l.status, count(l) from Lead l left join l.assignedCounsellor c
            where c.id = :userId or c is null group by l.status
            """)
    List<Object[]> countByStatusScoped(Long userId);

    @Query("select l.source, count(l) from Lead l group by l.source")
    List<Object[]> countBySource();

    @Query("""
            select l.source, count(l) from Lead l left join l.assignedCounsellor c
            where c.id = :userId or c is null group by l.source
            """)
    List<Object[]> countBySourceScoped(Long userId);

    long countByCreatedAtGreaterThanEqual(Instant since);

    @Query("""
            select count(l) from Lead l left join l.assignedCounsellor c
            where l.createdAt >= :since and (c.id = :userId or c is null)
            """)
    long countCreatedSinceScoped(Instant since, Long userId);
}
