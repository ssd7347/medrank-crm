package com.mbbscrm.crm.counselling;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.college.CollegeRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;
import com.mbbscrm.crm.counselling.CounsellingDtos.AllotmentRequest;
import com.mbbscrm.crm.counselling.CounsellingDtos.BulkAllotmentResult;
import com.mbbscrm.crm.counselling.CounsellingDtos.BulkError;
import com.mbbscrm.crm.security.CurrentUser;

/**
 * Result-day bulk entry (spec 4.6): one CSV per round with our students' outcomes. Columns: neet_roll_no,
 * college_code (blank = no allotment), course, quota, category (optional). Students are matched by roll
 * number within the round's authority and year. All-or-nothing: any bad row rejects the file.
 */
@Service
public class AllotmentImportService {

    private final RoundRepository rounds;
    private final StudentCounsellingRepository tracks;
    private final CollegeRepository colleges;
    private final CounsellingService counselling;

    public AllotmentImportService(RoundRepository rounds, StudentCounsellingRepository tracks,
                                  CollegeRepository colleges, CounsellingService counselling) {
        this.rounds = rounds;
        this.tracks = tracks;
        this.colleges = colleges;
        this.counselling = counselling;
    }

    private record Row(int line, String roll, String collegeCode, Course course, Quota quota, Category category) {
    }

    @Transactional
    public BulkAllotmentResult importCsv(Long roundId, MultipartFile file) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can upload a round's results");
        }
        CounsellingRoundEntity round = rounds.findById(roundId).orElseThrow(() -> ApiException.notFound("Round"));
        List<Row> rows = parse(file);

        Map<String, StudentCounselling> byRoll = new HashMap<>();
        tracks.findByRollNumbers(round.getAuthority().getId(), round.getAcademicYear(),
                rows.stream().map(Row::roll).toList()).forEach(t -> byRoll.put(t.getStudent().getNeetRollNo(), t));
        Map<String, College> byCode = new HashMap<>();
        colleges.findByCodeIn(rows.stream().map(Row::collegeCode).filter(c -> c != null).toList())
                .forEach(c -> byCode.put(c.getCode().toUpperCase(Locale.ROOT), c));

        List<BulkError> errors = new ArrayList<>();
        for (Row r : rows) {
            if (!byRoll.containsKey(r.roll())) {
                errors.add(new BulkError(r.line(), "No " + round.getAuthority().getCode() + " "
                        + round.getAcademicYear() + " track for roll number " + r.roll()));
            } else if (r.collegeCode() != null && !byCode.containsKey(r.collegeCode())) {
                errors.add(new BulkError(r.line(), "Unknown college_code " + r.collegeCode()));
            }
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("The file has errors; nothing was recorded")
                    .with("errors", errors.stream().limit(50).toList());
        }
        int recorded = 0;
        int none = 0;
        for (Row r : rows) {
            College c = r.collegeCode() == null ? null : byCode.get(r.collegeCode());
            counselling.record(byRoll.get(r.roll()),
                    new AllotmentRequest(round.getId(), c == null ? null : c.getId(), r.course(), r.quota(),
                            r.category()), me);
            if (c == null) {
                none++;
            } else {
                recorded++;
            }
        }
        return new BulkAllotmentResult(recorded, none, List.of());
    }

    private static List<Row> parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a CSV file to upload");
        }
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setIgnoreHeaderCase(true).get();
        List<Row> rows = new ArrayList<>();
        List<BulkError> errors = new ArrayList<>();
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            if (!parser.getHeaderMap().containsKey("neet_roll_no") || !parser.getHeaderMap().containsKey("college_code")) {
                throw ApiException.badRequest("CSV needs at least 'neet_roll_no' and 'college_code' columns "
                        + "(plus course, quota, category for allotted rows)");
            }
            for (CSVRecord rec : parser) {
                int line = (int) rec.getRecordNumber() + 1;
                if (rows.size() >= 5000) {
                    throw ApiException.badRequest("At most 5000 rows per upload");
                }
                try {
                    String roll = get(rec, "neet_roll_no");
                    if (roll == null) {
                        throw new IllegalArgumentException("neet_roll_no is required");
                    }
                    String code = get(rec, "college_code");
                    Course course = null;
                    Quota quota = null;
                    Category category = null;
                    if (code != null) {
                        course = parseEnum(Course.class, get(rec, "course"), "course", true);
                        quota = parseEnum(Quota.class, get(rec, "quota"), "quota", true);
                        category = parseEnum(Category.class, get(rec, "category"), "category", false);
                    }
                    rows.add(new Row(line, roll.toUpperCase(Locale.ROOT),
                            code == null ? null : code.toUpperCase(Locale.ROOT), course, quota, category));
                } catch (IllegalArgumentException e) {
                    errors.add(new BulkError(line, e.getMessage()));
                }
            }
        } catch (IOException | IllegalStateException e) {
            throw ApiException.badRequest("Could not read the CSV file. Save it as UTF-8 CSV and try again.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("The file has errors; nothing was recorded")
                    .with("errors", errors.stream().limit(50).toList());
        }
        if (rows.isEmpty()) {
            throw ApiException.badRequest("The CSV has no data rows");
        }
        return rows;
    }

    private static String get(CSVRecord rec, String col) {
        if (!rec.isMapped(col) || !rec.isSet(col)) {
            return null;
        }
        String v = rec.get(col);
        return v == null || v.isBlank() ? null : v.trim();
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v, String col, boolean required) {
        if (v == null) {
            if (required) {
                throw new IllegalArgumentException(col + " is required when a college is given");
            }
            return null;
        }
        try {
            return Enum.valueOf(type, v.toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(col + " '" + v + "' is not recognised");
        }
    }
}
