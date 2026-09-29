package com.mbbscrm.crm.counselling;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.counselling.CounsellingDtos.AuthorityRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.AuthorityResponse;
import com.mbbscrm.crm.counselling.CounsellingDtos.Deadline;
import com.mbbscrm.crm.counselling.CounsellingDtos.RoundRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.RoundResponse;
import com.mbbscrm.crm.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * Counselling authorities and their round calendars (spec 4.5, 4.15). Everyone reads; Data/Research staff
 * and admins maintain it. Every change is audited because alerts are driven by these dates.
 */
@RestController
@RequestMapping("/api/counselling")
public class CalendarController {

    private static final String EDITORS = "hasAnyRole('SUPER_ADMIN','DATA_EXEC')";

    private final AuthorityRepository authorities;
    private final RoundRepository rounds;
    private final AuditService audit;

    public CalendarController(AuthorityRepository authorities, RoundRepository rounds, AuditService audit) {
        this.authorities = authorities;
        this.rounds = rounds;
        this.audit = audit;
    }

    @GetMapping("/authorities")
    @Transactional(readOnly = true)
    public List<AuthorityResponse> authorities() {
        return authorities.findAllByOrderByAuthorityTypeAscNameAsc().stream().map(AuthorityResponse::of).toList();
    }

    @PostMapping("/authorities")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDITORS)
    @Transactional
    public AuthorityResponse createAuthority(@Valid @RequestBody AuthorityRequest req) {
        if (authorities.existsByCodeIgnoreCase(req.code())) {
            throw ApiException.conflict("An authority with code " + req.code() + " already exists");
        }
        CounsellingAuthority a = apply(new CounsellingAuthority(), req);
        authorities.save(a);
        audit.record(CurrentUser.get().id(), "AUTHORITY_CREATED", "COUNSELLING_AUTHORITY", a.getId(), a.getCode());
        return AuthorityResponse.of(a);
    }

    @PutMapping("/authorities/{id}")
    @PreAuthorize(EDITORS)
    @Transactional
    public AuthorityResponse updateAuthority(@PathVariable Long id, @Valid @RequestBody AuthorityRequest req) {
        CounsellingAuthority a = authorities.findById(id).orElseThrow(() -> ApiException.notFound("Authority"));
        if (!a.getCode().equalsIgnoreCase(req.code()) && authorities.existsByCodeIgnoreCase(req.code())) {
            throw ApiException.conflict("An authority with code " + req.code() + " already exists");
        }
        apply(a, req);
        audit.record(CurrentUser.get().id(), "AUTHORITY_UPDATED", "COUNSELLING_AUTHORITY", a.getId(), a.getCode());
        return AuthorityResponse.of(a);
    }

    @GetMapping("/rounds")
    @Transactional(readOnly = true)
    public List<RoundResponse> rounds(@RequestParam int year, @RequestParam(required = false) Long authorityId) {
        List<CounsellingRoundEntity> list = authorityId == null
                ? rounds.findByAcademicYearOrderByAuthorityIdAscRoundTypeAsc(year)
                : rounds.findByAuthorityIdAndAcademicYearOrderByRoundTypeAsc(authorityId, year);
        return list.stream().map(RoundResponse::of).toList();
    }

    @PostMapping("/rounds")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(EDITORS)
    @Transactional
    public RoundResponse createRound(@Valid @RequestBody RoundRequest req) {
        CounsellingAuthority a = authorities.findById(req.authorityId())
                .orElseThrow(() -> ApiException.badRequest("Authority not found"));
        rounds.findByAuthorityIdAndAcademicYearAndRoundType(a.getId(), req.academicYear(), req.roundType())
                .ifPresent(r -> {
                    throw ApiException.conflict(r.label() + " already exists; edit it instead");
                });
        CounsellingRoundEntity r = new CounsellingRoundEntity();
        r.setAuthority(a);
        r.setAcademicYear(req.academicYear());
        r.setRoundType(req.roundType());
        applyDates(r, req);
        rounds.save(r);
        audit.record(CurrentUser.get().id(), "ROUND_CREATED", "COUNSELLING_ROUND", r.getId(), r.label());
        return RoundResponse.of(r);
    }

    @PutMapping("/rounds/{id}")
    @PreAuthorize(EDITORS)
    @Transactional
    public RoundResponse updateRound(@PathVariable Long id, @Valid @RequestBody RoundRequest req) {
        CounsellingRoundEntity r = rounds.findById(id).orElseThrow(() -> ApiException.notFound("Round"));
        if (!r.getAuthority().getId().equals(req.authorityId()) || r.getAcademicYear() != req.academicYear()
                || r.getRoundType() != req.roundType()) {
            throw ApiException.badRequest("Authority, year and round type cannot be changed; create a new round");
        }
        applyDates(r, req);
        audit.record(CurrentUser.get().id(), "ROUND_UPDATED", "COUNSELLING_ROUND", r.getId(), r.label()
                + " choiceFillingEnd=" + r.getChoiceFillingEnd() + " reportingEnd=" + r.getReportingEnd());
        return RoundResponse.of(r);
    }

    /** Deadlines in the next {@code days} days across every authority, soonest first. */
    @GetMapping("/deadlines")
    @Transactional(readOnly = true)
    public List<Deadline> deadlines(@RequestParam(defaultValue = "14") int days) {
        return upcoming(rounds, Instant.now(), Math.clamp(days, 1, 120));
    }

    static List<Deadline> upcoming(RoundRepository rounds, Instant now, int days) {
        Instant to = now.plus(days, ChronoUnit.DAYS);
        List<Deadline> out = new ArrayList<>();
        for (CounsellingRoundEntity r : rounds.findWithDeadlineBetween(now, to)) {
            add(out, r, "Registration closes", r.getRegistrationEnd(), now, to);
            add(out, r, "Choice filling closes", r.getChoiceFillingEnd(), now, to);
            add(out, r, "Result", r.getResultAt(), now, to);
            add(out, r, "Reporting closes", r.getReportingEnd(), now, to);
        }
        out.sort(Comparator.comparing(Deadline::at));
        return out;
    }

    private static void add(List<Deadline> out, CounsellingRoundEntity r, String kind, Instant at, Instant from,
                            Instant to) {
        if (at != null && at.isAfter(from) && !at.isAfter(to)) {
            out.add(new Deadline(r.getId(), r.label(), kind, at));
        }
    }

    private static CounsellingAuthority apply(CounsellingAuthority a, AuthorityRequest req) {
        if (req.authorityType() == AuthorityType.STATE && (req.state() == null || req.state().isBlank())) {
            throw ApiException.badRequest("A state authority needs its state");
        }
        a.setCode(req.code().trim().toUpperCase(Locale.ROOT));
        a.setName(req.name().trim());
        a.setAuthorityType(req.authorityType());
        a.setState(req.authorityType() == AuthorityType.STATE ? req.state().trim() : null);
        a.setWebsite(req.website() == null || req.website().isBlank() ? null : req.website().trim());
        a.setActive(req.active());
        return a;
    }

    private static void applyDates(CounsellingRoundEntity r, RoundRequest req) {
        checkWindow("Registration", req.registrationStart(), req.registrationEnd());
        checkWindow("Choice filling", req.choiceFillingStart(), req.choiceFillingEnd());
        checkWindow("Reporting", req.reportingStart(), req.reportingEnd());
        if (req.choiceFillingEnd() != null && req.resultAt() != null && req.resultAt().isBefore(req.choiceFillingEnd())) {
            throw ApiException.badRequest("Result cannot be before choice filling closes");
        }
        if (req.resultAt() != null && req.reportingEnd() != null && req.reportingEnd().isBefore(req.resultAt())) {
            throw ApiException.badRequest("Reporting cannot close before the result is out");
        }
        r.setRegistrationStart(req.registrationStart());
        r.setRegistrationEnd(req.registrationEnd());
        r.setChoiceFillingStart(req.choiceFillingStart());
        r.setChoiceFillingEnd(req.choiceFillingEnd());
        r.setResultAt(req.resultAt());
        r.setReportingStart(req.reportingStart());
        r.setReportingEnd(req.reportingEnd());
        r.setNotes(req.notes() == null || req.notes().isBlank() ? null : req.notes().trim());
    }

    private static void checkWindow(String name, Instant start, Instant end) {
        if (start != null && end != null && end.isBefore(start)) {
            throw ApiException.badRequest(name + " closes before it opens");
        }
    }
}
