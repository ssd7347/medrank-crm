package com.mbbscrm.crm.college;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

public interface CutoffRecordRepository extends JpaRepository<CutoffRecord, Long> {

    List<CutoffRecord> findByCollegeIdOrderByAcademicYearDescCounsellingRoundAscQuotaAscCategoryAsc(Long collegeId);

    Optional<CutoffRecord> findByCollegeIdAndCourseAndQuotaAndCategoryAndPwdAndCounsellingRoundAndAcademicYear(
            Long collegeId, Course course, Quota quota, Category category, boolean pwd, CounsellingRound round,
            int academicYear);

    boolean existsByCollegeId(Long collegeId);
}
