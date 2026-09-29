package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.mbbscrm.crm.common.CounsellingRound;

public interface ChoiceListRepository extends JpaRepository<ChoiceList, Long> {
    Optional<ChoiceList> findByStudentCounsellingIdAndRoundId(Long studentCounsellingId, Long roundId);

    @EntityGraph(attributePaths = "round")
    List<ChoiceList> findByStudentCounsellingIdOrderByRoundRoundTypeAsc(Long studentCounsellingId);

    @Query("""
            select cl.studentCounselling.id from ChoiceList cl
            where cl.round.id = :roundId and cl.status = com.mbbscrm.crm.counselling.ChoiceListStatus.LOCKED
            """)
    List<Long> findLockedTrackIds(Long roundId);
}
