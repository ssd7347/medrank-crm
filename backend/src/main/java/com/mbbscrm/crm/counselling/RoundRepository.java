package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mbbscrm.crm.common.CounsellingRound;

public interface RoundRepository extends JpaRepository<CounsellingRoundEntity, Long> {
    @EntityGraph(attributePaths = "authority")
    List<CounsellingRoundEntity> findByAcademicYearOrderByAuthorityIdAscRoundTypeAsc(int academicYear);

    @EntityGraph(attributePaths = "authority")
    List<CounsellingRoundEntity> findByAuthorityIdAndAcademicYearOrderByRoundTypeAsc(Long authorityId, int year);

    Optional<CounsellingRoundEntity> findByAuthorityIdAndAcademicYearAndRoundType(Long authorityId, int year,
                                                                                CounsellingRound roundType);

    @EntityGraph(attributePaths = "authority")
    @Query("""
            select r from CounsellingRoundEntity r
            where r.choiceFillingEnd > :from and r.choiceFillingEnd <= :to
            """)
    List<CounsellingRoundEntity> findChoiceFillingClosingBetween(Instant from, Instant to);

    /** Rounds with any deadline between the two instants, for the upcoming-deadlines view. */
    @EntityGraph(attributePaths = "authority")
    @Query("""
            select r from CounsellingRoundEntity r
            where (r.registrationEnd > :from and r.registrationEnd <= :to)
               or (r.choiceFillingEnd > :from and r.choiceFillingEnd <= :to)
               or (r.resultAt > :from and r.resultAt <= :to)
               or (r.reportingEnd > :from and r.reportingEnd <= :to)
            """)
    List<CounsellingRoundEntity> findWithDeadlineBetween(Instant from, Instant to);
}
