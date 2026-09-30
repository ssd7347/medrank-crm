package com.mbbscrm.crm.alumni;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AlumniRepository extends JpaRepository<Alumni, Long> {

    @EntityGraph(attributePaths = {"student", "student.branch", "student.assignedCounsellor"})
    List<Alumni> findAllByOrderByAdmissionYearDescCreatedAtDesc();

    Optional<Alumni> findByStudentId(Long studentId);

    boolean existsByStudentId(Long studentId);
}
