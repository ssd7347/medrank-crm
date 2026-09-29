package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mbbscrm.crm.common.CounsellingRound;

public interface StudentCounsellingRepository extends JpaRepository<StudentCounselling, Long> {
    @EntityGraph(attributePaths = "authority")
    List<StudentCounselling> findByStudentIdOrderByAcademicYearDescAuthorityIdAsc(Long studentId);

    Optional<StudentCounselling> findByStudentIdAndAuthorityIdAndAcademicYear(Long studentId, Long authorityId,
                                                                             int year);

    @EntityGraph(attributePaths = {"student", "student.assignedCounsellor"})
    @Query("""
            select sc from StudentCounselling sc
            where sc.authority.id = :authorityId and sc.academicYear = :year and sc.status in :statuses
            """)
    List<StudentCounselling> findActive(Long authorityId, int year, Collection<CounsellingStatus> statuses);

    @EntityGraph(attributePaths = {"student", "authority"})
    @Query("""
            select sc from StudentCounselling sc
            where sc.authority.id = :authorityId and sc.academicYear = :year and sc.student.neetRollNo in :rolls
            """)
    List<StudentCounselling> findByRollNumbers(Long authorityId, int year, Collection<String> rolls);
}
