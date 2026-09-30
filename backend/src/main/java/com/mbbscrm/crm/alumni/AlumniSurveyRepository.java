package com.mbbscrm.crm.alumni;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlumniSurveyRepository extends JpaRepository<AlumniSurvey, Long> {

    @EntityGraph(attributePaths = "recordedBy")
    List<AlumniSurvey> findByAlumniIdOrderByRecordedAtDesc(Long alumniId);

    List<AlumniSurvey> findAllByOrderByRecordedAtDesc();
}
