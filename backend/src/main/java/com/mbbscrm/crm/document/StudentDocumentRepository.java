package com.mbbscrm.crm.document;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface StudentDocumentRepository extends JpaRepository<StudentDocument, Long> {

    @EntityGraph(attributePaths = {"documentType", "verifiedBy"})
    List<StudentDocument> findByStudentId(Long studentId);

    @EntityGraph(attributePaths = {"documentType"})
    List<StudentDocument> findByStudentIdIn(Collection<Long> studentIds);

    Optional<StudentDocument> findByStudentIdAndDocumentTypeId(Long studentId, Long documentTypeId);

    /** Uploaded or marked collected, waiting for someone to check the original. */
    @EntityGraph(attributePaths = {"documentType", "student", "student.assignedCounsellor"})
    List<StudentDocument> findByStatusOrderByUpdatedAtAsc(DocumentStatus status);

    @EntityGraph(attributePaths = {"documentType", "student", "student.assignedCounsellor"})
    @Query("""
            select d from StudentDocument d
            where d.validUntil is not null and d.validUntil <= :until
              and d.status <> com.mbbscrm.crm.document.DocumentStatus.NOT_COLLECTED
            order by d.validUntil asc
            """)
    List<StudentDocument> findExpiringBy(LocalDate until);
}
