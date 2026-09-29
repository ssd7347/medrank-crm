package com.mbbscrm.crm.approval;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Component;

import com.mbbscrm.crm.approval.DataChangeRequest.Action;
import com.mbbscrm.crm.approval.DataChangeRequest.EntityType;
import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.college.CollegeDtos.BulkCutoffPayload;
import com.mbbscrm.crm.college.CollegeDtos.BulkSeatMatrixPayload;
import com.mbbscrm.crm.college.CollegeDtos.CollegePayload;
import com.mbbscrm.crm.college.CollegeDtos.CollegeResponse;
import com.mbbscrm.crm.college.CollegeDtos.CutoffPayload;
import com.mbbscrm.crm.college.CollegeDtos.CutoffResponse;
import com.mbbscrm.crm.college.CollegeDtos.FeePayload;
import com.mbbscrm.crm.college.CollegeDtos.FeeResponse;
import com.mbbscrm.crm.college.CollegeDtos.SeatMatrixPayload;
import com.mbbscrm.crm.college.CollegeDtos.SeatMatrixResponse;
import com.mbbscrm.crm.college.CollegeFee;
import com.mbbscrm.crm.college.CollegeFeeRepository;
import com.mbbscrm.crm.college.CollegeRepository;
import com.mbbscrm.crm.college.CutoffRecord;
import com.mbbscrm.crm.college.CutoffRecordRepository;
import com.mbbscrm.crm.college.SeatMatrixEntry;
import com.mbbscrm.crm.college.SeatMatrixRepository;
import com.mbbscrm.crm.common.ApiException;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import tools.jackson.databind.ObjectMapper;

/**
 * Knows how to validate a proposed master-data change (at submit time, so bad data never reaches the review
 * queue) and how to apply it (at approval time, re-checking because data may have moved on meanwhile).
 */
@Component
class MasterDataApplier {

    record Prepared(String summary, String payloadJson) {
    }

    private final ObjectMapper mapper;
    private final Validator validator;
    private final CollegeRepository colleges;
    private final SeatMatrixRepository seats;
    private final CollegeFeeRepository fees;
    private final CutoffRecordRepository cutoffs;

    MasterDataApplier(ObjectMapper mapper, Validator validator, CollegeRepository colleges,
                      SeatMatrixRepository seats, CollegeFeeRepository fees, CutoffRecordRepository cutoffs) {
        this.mapper = mapper;
        this.validator = validator;
        this.colleges = colleges;
        this.seats = seats;
        this.fees = fees;
        this.cutoffs = cutoffs;
    }

    // ------------------------------------------------------------------ submit-time

    Prepared prepare(EntityType type, Action action, Long entityId, Object rawPayload) {
        if (action == Action.BULK_UPSERT && type != EntityType.SEAT_MATRIX && type != EntityType.CUTOFF) {
            throw ApiException.badRequest("Bulk upload is only supported for seat matrix and cutoffs");
        }
        if ((action == Action.UPDATE || action == Action.DELETE) && entityId == null) {
            throw ApiException.badRequest("entityId is required for " + action);
        }
        if (action == Action.CREATE || action == Action.BULK_UPSERT) {
            entityId = null;
        }
        if (action == Action.DELETE) {
            return new Prepared("Delete " + describeExisting(type, entityId), "{}");
        }
        Object dto = convertAndValidate(payloadClass(type, action), rawPayload);
        String summary = switch (dto) {
            case CollegePayload c -> checkCollege(c, entityId, action);
            case SeatMatrixPayload s -> checkSeat(s, entityId, action);
            case FeePayload f -> checkFee(f, entityId, action);
            case CutoffPayload c -> checkCutoff(c, entityId, action);
            case BulkSeatMatrixPayload b -> {
                b.rows().forEach(r -> requireCollege(r.collegeId()));
                yield "Bulk seat matrix upload: " + b.rows().size() + " rows";
            }
            case BulkCutoffPayload b -> {
                b.rows().forEach(r -> requireCollege(r.collegeId()));
                yield "Bulk cutoff upload: " + b.rows().size() + " rows";
            }
            default -> throw new IllegalStateException();
        };
        return new Prepared(truncate(summary), mapper.writeValueAsString(dto));
    }

    private String checkCollege(CollegePayload c, Long id, Action action) {
        if (action == Action.UPDATE) {
            requireCollege(id);
        }
        if (c.code() != null && !c.code().isBlank()) {
            colleges.findByCodeIgnoreCase(c.code().trim()).filter(other -> !other.getId().equals(id))
                    .ifPresent(other -> {
                        throw ApiException.conflict("College code " + c.code() + " is already used by "
                                + other.getName());
                    });
        }
        return (action == Action.CREATE ? "Add college: " : "Update college: ") + c.name();
    }

    private String checkSeat(SeatMatrixPayload s, Long id, Action action) {
        College c = requireCollege(s.collegeId());
        var existing = seats.findByCollegeIdAndCourseAndQuotaAndCategoryAndPwdAndCounsellingRoundAndAcademicYear(
                s.collegeId(), s.course(), s.quota(), s.category(), s.pwd(), s.counsellingRound(), s.academicYear());
        checkKey(action, id, existing.map(SeatMatrixEntry::getId).orElse(null), "seat matrix row");
        return "Seat matrix " + c.getName() + " " + s.course() + " " + s.quota() + "/" + s.category()
                + (s.pwd() ? "-PwD" : "") + " " + s.counsellingRound() + " " + s.academicYear() + ": " + s.seats()
                + " seats";
    }

    private String checkFee(FeePayload f, Long id, Action action) {
        College c = requireCollege(f.collegeId());
        var existing = fees.findByCollegeIdAndCourseAndQuotaAndAcademicYear(f.collegeId(), f.course(), f.quota(),
                f.academicYear());
        checkKey(action, id, existing.map(CollegeFee::getId).orElse(null), "fee row");
        return "Fee " + c.getName() + " " + f.course() + " " + f.quota() + " " + f.academicYear() + ": "
                + f.annualTuition() + "/year";
    }

    private String checkCutoff(CutoffPayload r, Long id, Action action) {
        College c = requireCollege(r.collegeId());
        var existing = cutoffs.findByCollegeIdAndCourseAndQuotaAndCategoryAndPwdAndCounsellingRoundAndAcademicYear(
                r.collegeId(), r.course(), r.quota(), r.category(), r.pwd(), r.counsellingRound(), r.academicYear());
        checkKey(action, id, existing.map(CutoffRecord::getId).orElse(null), "cutoff row");
        return "Cutoff " + c.getName() + " " + r.course() + " " + r.quota() + "/" + r.category()
                + (r.pwd() ? "-PwD" : "") + " " + r.counsellingRound() + " " + r.academicYear() + ": rank "
                + r.closingRank();
    }

    /** CREATE must not collide with an existing key; UPDATE may only collide with itself. */
    private static void checkKey(Action action, Long id, Long existingId, String what) {
        if (action == Action.UPDATE && id == null) {
            throw ApiException.badRequest("entityId is required");
        }
        if (existingId != null && !existingId.equals(id)) {
            throw ApiException.conflict("A " + what + " for this college/course/quota/category/round/year already "
                    + "exists (#" + existingId + "). Edit that one instead.");
        }
    }

    // ------------------------------------------------------------------ approval-time

    /** Applies the change and returns the id of the affected record (null for bulk). */
    Long apply(DataChangeRequest r) {
        EntityType type = r.getEntityType();
        Long id = r.getEntityId();
        if (r.getAction() == Action.DELETE) {
            delete(type, id);
            return id;
        }
        // Re-run the checks: keys or referenced colleges may have changed since submission.
        Object dto = convertAndValidate(payloadClass(type, r.getAction()), mapper.readTree(r.getPayload()));
        prepare(type, r.getAction(), id, mapper.readTree(r.getPayload()));
        return switch (dto) {
            case CollegePayload c -> {
                College e = id == null ? new College() : requireCollege(id);
                e.setName(c.name().trim());
                e.setCode(c.code() == null || c.code().isBlank() ? null : c.code().trim().toUpperCase());
                e.setCollegeType(c.collegeType());
                e.setState(c.state().trim());
                e.setCity(blankToNull(c.city()));
                e.setAffiliatedUniversity(blankToNull(c.affiliatedUniversity()));
                e.setNmcRecognized(c.nmcRecognized());
                e.setEstablishedYear(c.establishedYear());
                e.setWebsite(blankToNull(c.website()));
                yield colleges.save(e).getId();
            }
            case SeatMatrixPayload s -> {
                SeatMatrixEntry e = id == null ? new SeatMatrixEntry() : seats.findById(id)
                        .orElseThrow(() -> ApiException.notFound("Seat matrix row"));
                yield seats.save(fillSeat(e, s)).getId();
            }
            case FeePayload f -> {
                CollegeFee e = id == null ? new CollegeFee() : fees.findById(id)
                        .orElseThrow(() -> ApiException.notFound("Fee row"));
                e.setCollegeId(f.collegeId());
                e.setCourse(f.course());
                e.setQuota(f.quota());
                e.setAcademicYear(f.academicYear());
                e.setAnnualTuition(f.annualTuition());
                e.setOtherFees(f.otherFees());
                e.setNotes(blankToNull(f.notes()));
                yield fees.save(e).getId();
            }
            case CutoffPayload c -> {
                CutoffRecord e = id == null ? new CutoffRecord() : cutoffs.findById(id)
                        .orElseThrow(() -> ApiException.notFound("Cutoff row"));
                yield cutoffs.save(fillCutoff(e, c)).getId();
            }
            case BulkSeatMatrixPayload b -> {
                for (SeatMatrixPayload s : b.rows()) {
                    SeatMatrixEntry e = seats
                            .findByCollegeIdAndCourseAndQuotaAndCategoryAndPwdAndCounsellingRoundAndAcademicYear(
                                    s.collegeId(), s.course(), s.quota(), s.category(), s.pwd(), s.counsellingRound(),
                                    s.academicYear())
                            .orElseGet(SeatMatrixEntry::new);
                    seats.save(fillSeat(e, s));
                }
                yield null;
            }
            case BulkCutoffPayload b -> {
                for (CutoffPayload c : b.rows()) {
                    CutoffRecord e = cutoffs
                            .findByCollegeIdAndCourseAndQuotaAndCategoryAndPwdAndCounsellingRoundAndAcademicYear(
                                    c.collegeId(), c.course(), c.quota(), c.category(), c.pwd(), c.counsellingRound(),
                                    c.academicYear())
                            .orElseGet(CutoffRecord::new);
                    cutoffs.save(fillCutoff(e, c));
                }
                yield null;
            }
            default -> throw new IllegalStateException();
        };
    }

    private void delete(EntityType type, Long id) {
        switch (type) {
            case COLLEGE -> {
                College c = requireCollege(id);
                if (seats.existsByCollegeId(id) || fees.existsByCollegeId(id) || cutoffs.existsByCollegeId(id)) {
                    throw ApiException.conflict("Delete this college's seat matrix, fee and cutoff rows first");
                }
                colleges.delete(c);
            }
            case SEAT_MATRIX -> seats.delete(seats.findById(id).orElseThrow(() -> ApiException.notFound("Row")));
            case FEE -> fees.delete(fees.findById(id).orElseThrow(() -> ApiException.notFound("Row")));
            case CUTOFF -> cutoffs.delete(cutoffs.findById(id).orElseThrow(() -> ApiException.notFound("Row")));
        }
    }

    /** The live record a change request targets, so the reviewer can compare before/after. */
    Object current(EntityType type, Long id) {
        if (id == null) {
            return null;
        }
        return switch (type) {
            case COLLEGE -> colleges.findById(id).map(CollegeResponse::of).orElse(null);
            case SEAT_MATRIX -> seats.findById(id).map(SeatMatrixResponse::of).orElse(null);
            case FEE -> fees.findById(id).map(FeeResponse::of).orElse(null);
            case CUTOFF -> cutoffs.findById(id).map(CutoffResponse::of).orElse(null);
        };
    }

    // ------------------------------------------------------------------ helpers

    private static Class<?> payloadClass(EntityType type, Action action) {
        boolean bulk = action == Action.BULK_UPSERT;
        return switch (type) {
            case COLLEGE -> CollegePayload.class;
            case SEAT_MATRIX -> bulk ? BulkSeatMatrixPayload.class : SeatMatrixPayload.class;
            case FEE -> FeePayload.class;
            case CUTOFF -> bulk ? BulkCutoffPayload.class : CutoffPayload.class;
        };
    }

    private Object convertAndValidate(Class<?> cls, Object raw) {
        if (raw == null) {
            throw ApiException.badRequest("payload is required");
        }
        Object dto;
        try {
            dto = mapper.convertValue(raw, cls);
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Payload has missing or invalid fields");
        }
        Set<ConstraintViolation<Object>> violations = validator.validate(dto);
        if (!violations.isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            violations.stream().limit(50)
                    .forEach(v -> errors.putIfAbsent(v.getPropertyPath().toString(), v.getMessage()));
            throw ApiException.badRequest("Some fields are invalid").with("errors", errors);
        }
        return dto;
    }

    private String describeExisting(EntityType type, Long id) {
        Object cur = current(type, id);
        if (cur == null) {
            throw ApiException.notFound(type.name().toLowerCase().replace('_', ' ') + " #" + id);
        }
        return switch (cur) {
            case CollegeResponse c -> "college: " + c.name();
            default -> type.name().toLowerCase().replace('_', ' ') + " row #" + id;
        };
    }

    private College requireCollege(Long id) {
        return colleges.findById(id).orElseThrow(() -> ApiException.badRequest("College #" + id + " not found"));
    }

    private static SeatMatrixEntry fillSeat(SeatMatrixEntry e, SeatMatrixPayload s) {
        e.setCollegeId(s.collegeId());
        e.setCourse(s.course());
        e.setQuota(s.quota());
        e.setCategory(s.category());
        e.setPwd(s.pwd());
        e.setCounsellingRound(s.counsellingRound());
        e.setAcademicYear(s.academicYear());
        e.setSeats(s.seats());
        return e;
    }

    private static CutoffRecord fillCutoff(CutoffRecord e, CutoffPayload c) {
        e.setCollegeId(c.collegeId());
        e.setCourse(c.course());
        e.setQuota(c.quota());
        e.setCategory(c.category());
        e.setPwd(c.pwd());
        e.setCounsellingRound(c.counsellingRound());
        e.setAcademicYear(c.academicYear());
        e.setClosingRank(c.closingRank());
        return e;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String truncate(String s) {
        return s.length() <= 300 ? s : s.substring(0, 297) + "...";
    }
}
