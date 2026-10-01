package com.mbbscrm.crm.student;

import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface StudentRepository extends JpaRepository<Student, Long>, JpaSpecificationExecutor<Student> {

    @Override
    @EntityGraph(attributePaths = "assignedCounsellor")
    Page<Student> findAll(Specification<Student> spec, Pageable pageable);

    Optional<Student> findByNeetRollNo(String neetRollNo);

    java.util.List<Student> findByPhone(String phone);

    /** Whoever this number belongs to, as the student or as the parent (used to recognise an incoming call). */
    java.util.List<Student> findByPhoneOrParentPhone(String phone, String parentPhone);

    long countByAssignedCounsellorId(Long counsellorId);
}
