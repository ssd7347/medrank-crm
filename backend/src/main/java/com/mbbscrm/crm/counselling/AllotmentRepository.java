package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mbbscrm.crm.common.CounsellingRound;

public interface AllotmentRepository extends JpaRepository<AllotmentResult, Long> {
    Optional<AllotmentResult> findByStudentCounsellingIdAndRoundId(Long studentCounsellingId, Long roundId);

    @EntityGraph(attributePaths = {"round", "college"})
    List<AllotmentResult> findByStudentCounsellingIdOrderByRoundRoundTypeAsc(Long studentCounsellingId);

    /** Allotted seats still waiting for a decision whose deadline falls in the window. */
    @EntityGraph(attributePaths = {"studentCounselling", "studentCounselling.student",
            "studentCounselling.student.assignedCounsellor", "college", "round", "round.authority"})
    @Query("""
            select a from AllotmentResult a
            where a.college is not null and a.decision is null
              and a.decisionDeadline > :from and a.decisionDeadline <= :to
            """)
    List<AllotmentResult> findUndecidedDueBetween(Instant from, Instant to);

    @EntityGraph(attributePaths = {"studentCounselling", "studentCounselling.student",
            "studentCounselling.student.assignedCounsellor", "college", "round", "round.authority"})
    @Query("""
            select a from AllotmentResult a
            where a.college is not null and a.decision is null and a.decisionDeadline > :now
            order by a.decisionDeadline asc
            """)
    List<AllotmentResult> findAllUndecidedOpen(Instant now);

    long countByRoundId(Long roundId);
}
