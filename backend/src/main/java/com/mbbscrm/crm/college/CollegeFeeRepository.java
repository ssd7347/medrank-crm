package com.mbbscrm.crm.college;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

public interface CollegeFeeRepository extends JpaRepository<CollegeFee, Long> {

    List<CollegeFee> findByCollegeIdOrderByAcademicYearDescQuotaAsc(Long collegeId);

    Optional<CollegeFee> findByCollegeIdAndCourseAndQuotaAndAcademicYear(Long collegeId, Course course, Quota quota,
                                                                          int academicYear);

    boolean existsByCollegeId(Long collegeId);
}
