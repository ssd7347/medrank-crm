package com.mbbscrm.crm.college;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.college.CollegeDtos.CollegeDetail;
import com.mbbscrm.crm.college.CollegeDtos.CollegeResponse;
import com.mbbscrm.crm.college.CollegeDtos.CutoffResponse;
import com.mbbscrm.crm.college.CollegeDtos.FeeResponse;
import com.mbbscrm.crm.college.CollegeDtos.SeatMatrixResponse;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.CollegeType;
import com.mbbscrm.crm.common.PageResponse;

import jakarta.persistence.criteria.Predicate;

/** Read-only access to approved master data, open to all staff. Writes go through change requests. */
@RestController
@RequestMapping("/api/colleges")
public class CollegeController {

    private final CollegeRepository colleges;
    private final SeatMatrixRepository seats;
    private final CollegeFeeRepository fees;
    private final CutoffRecordRepository cutoffs;

    public CollegeController(CollegeRepository colleges, SeatMatrixRepository seats, CollegeFeeRepository fees,
                             CutoffRecordRepository cutoffs) {
        this.colleges = colleges;
        this.seats = seats;
        this.fees = fees;
        this.cutoffs = cutoffs;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public PageResponse<CollegeResponse> search(@RequestParam(required = false) String q,
                                                @RequestParam(required = false) String state,
                                                @RequestParam(required = false) CollegeType type,
                                                @RequestParam(defaultValue = "0") int page,
                                                @RequestParam(defaultValue = "25") int size) {
        Specification<College> spec = (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            if (q != null && !q.isBlank()) {
                String like = "%" + q.trim().toLowerCase(Locale.ROOT) + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("name")), like), cb.like(cb.lower(root.get("code")), like),
                        cb.like(cb.lower(root.get("city")), like)));
            }
            if (state != null && !state.isBlank()) {
                p.add(cb.equal(root.get("state"), state));
            }
            if (type != null) {
                p.add(cb.equal(root.get("collegeType"), type));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
        return PageResponse.of(colleges.findAll(spec,
                PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 200), Sort.by("state", "name"))),
                CollegeResponse::of);
    }

    @GetMapping("/{id}")
    @Transactional(readOnly = true)
    public CollegeDetail get(@PathVariable Long id) {
        College c = colleges.findById(id).orElseThrow(() -> ApiException.notFound("College"));
        return new CollegeDetail(CollegeResponse.of(c),
                seats.findByCollegeIdOrderByAcademicYearDescCounsellingRoundAscQuotaAscCategoryAsc(id).stream()
                        .map(SeatMatrixResponse::of).toList(),
                fees.findByCollegeIdOrderByAcademicYearDescQuotaAsc(id).stream().map(FeeResponse::of).toList(),
                cutoffs.findByCollegeIdOrderByAcademicYearDescCounsellingRoundAscQuotaAscCategoryAsc(id).stream()
                        .map(CutoffResponse::of).toList());
    }
}
