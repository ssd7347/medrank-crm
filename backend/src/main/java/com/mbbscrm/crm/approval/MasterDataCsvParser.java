package com.mbbscrm.crm.approval;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.college.CollegeDtos.BulkCutoffPayload;
import com.mbbscrm.crm.college.CollegeDtos.BulkSeatMatrixPayload;
import com.mbbscrm.crm.college.CollegeDtos.CutoffPayload;
import com.mbbscrm.crm.college.CollegeDtos.SeatMatrixPayload;
import com.mbbscrm.crm.college.CollegeRepository;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.CounsellingRound;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

/**
 * Parses seat-matrix / cutoff CSVs published each round into one bulk change request. Colleges are matched by
 * their code. Columns: college_code, course, quota, category, pwd, round, academic_year, and then seats
 * (seat matrix) or closing_rank (cutoffs). Any bad row rejects the whole file, with every error listed.
 */
@Component
class MasterDataCsvParser {

    private static final int MAX_ROWS = 5000;

    private final CollegeRepository colleges;

    MasterDataCsvParser(CollegeRepository colleges) {
        this.colleges = colleges;
    }

    BulkSeatMatrixPayload parseSeatMatrix(MultipartFile file) {
        List<SeatMatrixPayload> rows = new ArrayList<>();
        parse(file, "seats", (rec, key, value) -> rows.add(new SeatMatrixPayload(key.collegeId(), key.course(),
                key.quota(), key.category(), key.pwd(), key.round(), key.year(), value)));
        return new BulkSeatMatrixPayload(rows);
    }

    BulkCutoffPayload parseCutoffs(MultipartFile file) {
        List<CutoffPayload> rows = new ArrayList<>();
        parse(file, "closing_rank", (rec, key, value) -> rows.add(new CutoffPayload(key.collegeId(), key.course(),
                key.quota(), key.category(), key.pwd(), key.round(), key.year(), value)));
        return new BulkCutoffPayload(rows);
    }

    private record Key(Long collegeId, Course course, Quota quota, Category category, boolean pwd,
                       CounsellingRound round, int year) {
    }

    private interface RowSink {
        void accept(CSVRecord rec, Key key, int value);
    }

    private void parse(MultipartFile file, String valueColumn, RowSink sink) {
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose a CSV file to upload");
        }
        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true)
                .setIgnoreEmptyLines(true).setTrim(true).setIgnoreHeaderCase(true).get();
        List<String> required = List.of("college_code", "course", "quota", "category", "round", "academic_year",
                valueColumn);
        Map<String, String> errors = new LinkedHashMap<>();
        try (Reader reader = new InputStreamReader(file.getInputStream(), StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            for (String col : required) {
                if (!parser.getHeaderMap().containsKey(col)) {
                    throw ApiException.badRequest("CSV is missing the '" + col + "' column. Required: "
                            + String.join(", ", required) + " (pwd optional)");
                }
            }
            List<CSVRecord> records = parser.getRecords();
            if (records.size() > MAX_ROWS) {
                throw ApiException.badRequest("At most " + MAX_ROWS + " rows per upload; split the file");
            }
            if (records.isEmpty()) {
                throw ApiException.badRequest("The CSV has no data rows");
            }
            Set<String> codes = new HashSet<>();
            records.forEach(r -> codes.add(r.get("college_code").trim().toUpperCase(Locale.ROOT)));
            Map<String, Long> idByCode = new HashMap<>();
            for (College c : colleges.findByCodeIn(codes)) {
                idByCode.put(c.getCode().toUpperCase(Locale.ROOT), c.getId());
            }
            Set<Key> seen = new HashSet<>();
            for (CSVRecord rec : records) {
                String row = "row " + (rec.getRecordNumber() + 1);
                try {
                    String code = rec.get("college_code").trim().toUpperCase(Locale.ROOT);
                    Long collegeId = idByCode.get(code);
                    if (collegeId == null) {
                        throw new IllegalArgumentException("unknown college_code '" + code + "'");
                    }
                    boolean pwd = rec.isMapped("pwd") && isTrue(rec.get("pwd"));
                    Key key = new Key(collegeId, parseEnum(Course.class, rec.get("course"), "course"),
                            parseEnum(Quota.class, rec.get("quota"), "quota"),
                            parseEnum(Category.class, rec.get("category"), "category"), pwd,
                            parseEnum(CounsellingRound.class, rec.get("round"), "round"),
                            parseInt(rec.get("academic_year"), "academic_year"));
                    if (!seen.add(key)) {
                        throw new IllegalArgumentException("duplicate of an earlier row");
                    }
                    sink.accept(rec, key, parseInt(rec.get(valueColumn), valueColumn));
                } catch (IllegalArgumentException e) {
                    if (errors.size() < 50) {
                        errors.put(row, e.getMessage());
                    }
                }
            }
        } catch (IOException | IllegalStateException e) {
            throw ApiException.badRequest("Could not read the CSV file. Save it as UTF-8 CSV and try again.");
        }
        if (!errors.isEmpty()) {
            throw ApiException.badRequest("The file has errors; nothing was submitted").with("errors", errors);
        }
    }

    private static boolean isTrue(String v) {
        String s = v == null ? "" : v.trim().toLowerCase(Locale.ROOT);
        return s.equals("true") || s.equals("yes") || s.equals("y") || s.equals("1");
    }

    private static int parseInt(String v, String column) {
        try {
            return Integer.parseInt(v.trim().replace(",", ""));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(column + " must be a whole number");
        }
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String v, String column) {
        try {
            return Enum.valueOf(type, v.trim().toUpperCase(Locale.ROOT).replace(' ', '_').replace('-', '_'));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(column + " '" + v + "' is not recognised");
        }
    }
}
