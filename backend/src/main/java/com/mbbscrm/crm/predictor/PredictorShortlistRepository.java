package com.mbbscrm.crm.predictor;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

public interface PredictorShortlistRepository extends JpaRepository<PredictorShortlist, Long> {

    @EntityGraph(attributePaths = "college")
    List<PredictorShortlist> findByStudentIdOrderByCreatedAtAsc(Long studentId);

    boolean existsByStudentIdAndCollegeIdAndCourseAndQuota(Long studentId, Long collegeId, Course course, Quota quota);
}
