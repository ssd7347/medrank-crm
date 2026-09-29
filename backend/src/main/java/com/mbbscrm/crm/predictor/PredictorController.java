package com.mbbscrm.crm.predictor;

import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.college.CollegeRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.predictor.PredictorDtos.PredictRequest;
import com.mbbscrm.crm.predictor.PredictorDtos.PredictResponse;
import com.mbbscrm.crm.predictor.PredictorDtos.ShortlistItem;
import com.mbbscrm.crm.predictor.PredictorDtos.ShortlistRequest;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.student.StudentService;

import jakarta.validation.Valid;

@RestController
@RequestMapping("/api")
public class PredictorController {

    private final PredictorService predictor;
    private final PredictorShortlistRepository shortlist;
    private final CollegeRepository colleges;
    private final StudentService students;

    public PredictorController(PredictorService predictor, PredictorShortlistRepository shortlist,
                               CollegeRepository colleges, StudentService students) {
        this.predictor = predictor;
        this.shortlist = shortlist;
        this.colleges = colleges;
        this.students = students;
    }

    /** Open to all staff: telecallers use it on the phone with new leads. */
    @PostMapping("/predictor")
    public PredictResponse predict(@Valid @RequestBody PredictRequest req) {
        return predictor.predict(req);
    }

    @GetMapping("/students/{studentId}/shortlist")
    @Transactional(readOnly = true)
    public List<ShortlistItem> shortlist(@PathVariable Long studentId) {
        students.requireReadable(studentId);
        return shortlist.findByStudentIdOrderByCreatedAtAsc(studentId).stream().map(PredictorController::toItem)
                .toList();
    }

    @PostMapping("/students/{studentId}/shortlist")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public ShortlistItem add(@PathVariable Long studentId, @Valid @RequestBody ShortlistRequest req) {
        students.requireWritable(studentId);
        if (shortlist.existsByStudentIdAndCollegeIdAndCourseAndQuota(studentId, req.collegeId(), req.course(),
                req.quota())) {
            throw ApiException.conflict("Already on this student's shortlist");
        }
        College c = colleges.findById(req.collegeId()).orElseThrow(() -> ApiException.badRequest("College not found"));
        PredictorShortlist s = shortlist.save(new PredictorShortlist(studentId, c, req.course(), req.quota(),
                req.band(), req.note() == null || req.note().isBlank() ? null : req.note().trim(),
                CurrentUser.get().id()));
        return toItem(s);
    }

    @DeleteMapping("/students/{studentId}/shortlist/{itemId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void remove(@PathVariable Long studentId, @PathVariable Long itemId) {
        students.requireWritable(studentId);
        PredictorShortlist s = shortlist.findById(itemId).filter(x -> x.getStudentId().equals(studentId))
                .orElseThrow(() -> ApiException.notFound("Shortlist item"));
        shortlist.delete(s);
    }

    private static ShortlistItem toItem(PredictorShortlist s) {
        College c = s.getCollege();
        return new ShortlistItem(s.getId(), c.getId(), c.getName(), c.getState(), c.getCollegeType(), s.getCourse(),
                s.getQuota(), s.getBand(), s.getNote(), s.getCreatedAt());
    }
}
