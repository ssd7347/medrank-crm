package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.FieldDefinition;
import com.aicallcrm.crm.contact.FieldService;
import com.aicallcrm.crm.contact.ImportService;
import com.aicallcrm.crm.voice.Voice.FieldOp;
import com.aicallcrm.crm.voice.Voice.SkipReason;

/** The pure rules of the voice agent: calling hours in Indian time, retries, desk hours, signatures, names. */
class VoiceRulesTest {

    private static final LocalTime NINE = LocalTime.of(9, 0);
    private static final LocalTime EIGHT_PM = LocalTime.of(20, 0);
    private static final String SECRET = "unit-test-secret-0123456789-abcdefghijkl";

    /** 5 October 2026 is a Monday. */
    private static Instant ist(int day, int hour, int minute) {
        return ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, ContactFacts.IST).toInstant();
    }

    @Test
    void callingWindowIncludesItsStartAndExcludesItsEnd() {
        assertThat(CallEligibility.inWindow(NINE, EIGHT_PM, ist(5, 8, 59))).isFalse();
        assertThat(CallEligibility.inWindow(NINE, EIGHT_PM, ist(5, 9, 0))).isTrue();
        assertThat(CallEligibility.inWindow(NINE, EIGHT_PM, ist(5, 19, 59))).isTrue();
        assertThat(CallEligibility.inWindow(NINE, EIGHT_PM, ist(5, 20, 0))).isFalse();
        // The window is judged in Indian time whatever the server's own time zone: 03:30 UTC is 09:00 IST.
        assertThat(CallEligibility.inWindow(NINE, EIGHT_PM, Instant.parse("2026-10-05T03:30:00Z"))).isTrue();
        assertThat(CallEligibility.inWindow(NINE, EIGHT_PM, Instant.parse("2026-10-05T03:29:00Z"))).isFalse();
        // A campaign window that does not overlap the legal one leaves nothing open.
        assertThat(CallEligibility.inWindow(EIGHT_PM, NINE, ist(5, 12, 0))).isFalse();
    }

    @Test
    void nextWindowOpensTodayIfStillAheadOtherwiseTomorrow() {
        assertThat(CallEligibility.nextWindowStart(NINE, EIGHT_PM, ist(5, 7, 0))).isEqualTo(ist(5, 9, 0));
        assertThat(CallEligibility.nextWindowStart(NINE, EIGHT_PM, ist(5, 21, 0))).isEqualTo(ist(6, 9, 0));
        assertThat(CallEligibility.later(NINE, LocalTime.of(10, 0))).isEqualTo(LocalTime.of(10, 0));
        assertThat(CallEligibility.earlier(EIGHT_PM, LocalTime.of(18, 0))).isEqualTo(LocalTime.of(18, 0));
    }

    @Test
    void retryWaitsForTheGapAndMovesToTheOtherHalfOfTheDay() {
        // Morning attempt: not before the afternoon, even with a short gap.
        assertThat(CallEligibility.nextRetry(ist(5, 10, 0), 60, NINE, EIGHT_PM)).isEqualTo(ist(5, 14, 0));
        assertThat(CallEligibility.nextRetry(ist(5, 10, 0), 300, NINE, EIGHT_PM)).isEqualTo(ist(5, 15, 0));
        // Afternoon attempt: next morning.
        assertThat(CallEligibility.nextRetry(ist(5, 16, 0), 240, NINE, EIGHT_PM)).isEqualTo(ist(6, 9, 0));
        // A full-day gap is respected as it is.
        assertThat(CallEligibility.nextRetry(ist(5, 19, 0), 1440, NINE, EIGHT_PM)).isEqualTo(ist(6, 19, 0));
        // A retry that would land after hours moves to the next opening.
        assertThat(CallEligibility.nextRetry(ist(5, 13, 30), 480, NINE, EIGHT_PM)).isEqualTo(ist(6, 9, 0));
    }

    @Test
    void deskHoursSkipEveningsAndSundays() {
        LocalTime open = LocalTime.of(9, 30);
        LocalTime close = LocalTime.of(18, 30);
        assertThat(HandoffService.deskTime(ist(5, 10, 0), open, close)).isEqualTo(ist(5, 10, 0));
        assertThat(HandoffService.deskTime(ist(5, 19, 0), open, close)).isEqualTo(ist(6, 9, 30));
        assertThat(HandoffService.deskTime(ist(5, 8, 0), open, close)).isEqualTo(ist(5, 9, 30));
        // Saturday evening and all of Sunday (the 11th) roll over to Monday the 12th.
        assertThat(HandoffService.deskTime(ist(10, 19, 0), open, close)).isEqualTo(ist(12, 9, 30));
        assertThat(HandoffService.deskTime(ist(11, 11, 0), open, close)).isEqualTo(ist(12, 9, 30));
        assertThat(HandoffService.deskTime(ist(11, 8, 0), open, close)).isEqualTo(ist(12, 9, 30));
    }

    @Test
    void onlyPermanentReasonsEndATarget() {
        assertThat(List.of(SkipReason.NO_CONSENT, SkipReason.DND, SkipReason.OPTED_OUT, SkipReason.DO_NOT_CALL, SkipReason.MAX_ATTEMPTS,
                SkipReason.NO_LONGER_NEEDED)).allMatch(SkipReason::permanent);
        assertThat(List.of(SkipReason.OUTSIDE_WINDOW, SkipReason.NOT_DUE_YET, SkipReason.CALLED_RECENTLY,
                SkipReason.DAILY_LIMIT)).noneMatch(SkipReason::permanent);
    }

    @Test
    void signatureCoversTheBodyAndTheTimestamp() {
        VoiceSignatures signatures = new VoiceSignatures(props(SECRET));
        String body = "{\"call_id\":\"x\",\"args\":{}}";
        String now = String.valueOf(Instant.now().getEpochSecond());
        String good = VoiceSignatures.sign(SECRET, now, body);

        signatures.verify(body, good, now);
        assertThatThrownBy(() -> signatures.verify(body + " ", good, now)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> signatures.verify(body, null, now)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> signatures.verify(body, good, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> signatures.verify(body, good, "not-a-number")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> signatures.verify(body, VoiceSignatures.sign("another-secret-0123456789-abcdefghijk",
                now, body), now)).isInstanceOf(ApiException.class);

        // A captured request cannot be replayed later, even if the attacker rewrites the timestamp header.
        String old = String.valueOf(Instant.now().getEpochSecond() - 301);
        assertThatThrownBy(() -> signatures.verify(body, VoiceSignatures.sign(SECRET, old, body), old))
                .isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> signatures.verify(body, VoiceSignatures.sign(SECRET, old, body), now))
                .isInstanceOf(ApiException.class);

        // With no secret configured nothing gets in, not even a properly signed request.
        VoiceSignatures unconfigured = new VoiceSignatures(props(""));
        assertThatThrownBy(() -> unconfigured.verify(body, good, now))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void nameCheckNeedsEveryWordButForgivesInitialsCaseAndOrder() {
        assertThat(VoiceTools.nameMatches("priya shankar", "Priya Shankar")).isTrue();
        assertThat(VoiceTools.nameMatches("Shankar, Priya N.", "Priya Shankar N")).isTrue();
        assertThat(VoiceTools.nameMatches("Priya", "Priya Shankar")).isFalse();
        assertThat(VoiceTools.nameMatches("Divya Shankar", "Priya Shankar")).isFalse();
        assertThat(VoiceTools.nameMatches(null, "Priya Shankar")).isFalse();
        assertThat(VoiceTools.nameMatches("anything", "")).isFalse();
    }

    @Test
    void nameCheckForgivesHowTheEarSpellsIndianNamesButNotADifferentName() {
        // What Whisper actually wrote on the phone line (ear check, 11 Nov), and common spellings of the same name.
        assertThat(VoiceTools.nameMatches("Surish Kumar", "Suresh Kumar")).isTrue();
        assertThat(VoiceTools.nameMatches("Sirish Kumar", "Suresh Kumar")).isTrue();
        assertThat(VoiceTools.nameMatches("Mohammed Erfan", "Mohammed Irfan")).isTrue();
        assertThat(VoiceTools.nameMatches("Muhammad Erfin", "Mohammed Irfan")).isTrue();
        assertThat(VoiceTools.nameMatches("Laxminarayanan", "Lakshmi Narayanan")).isTrue();
        assertThat(VoiceTools.nameMatches("Laxmina Rianan", "Lakshmi Narayanan")).isTrue();
        assertThat(VoiceTools.nameMatches("Laxmi Narayanan", "Lakshmi Narayanan")).isTrue();
        assertThat(VoiceTools.nameMatches("Dipak Sharma", "Deepak Sharma")).isTrue();
        assertThat(VoiceTools.nameMatches("Pavan Kumar", "Pawan Kumar")).isTrue();
        assertThat(VoiceTools.nameMatches("Karthick Ragavan", "Karthik Raghavan")).isTrue();
        assertThat(VoiceTools.nameMatches("Venkatesh Prasad", "Venkatesh Prasad")).isTrue();
        assertThat(VoiceTools.nameMatches("Meenaiyer", "Meena Iyer")).isTrue();

        // A different name, a missing surname, or what the ear made of a name it did not catch: still no.
        assertThat(VoiceTools.nameMatches("Rajesh Kumar", "Ramesh Kumar")).isFalse();
        assertThat(VoiceTools.nameMatches("Sudesh Kumar", "Suresh Kumar")).isFalse();
        assertThat(VoiceTools.nameMatches("Mahesh Kumar", "Mukesh Kumar")).isFalse();
        assertThat(VoiceTools.nameMatches("Divya Shankar", "Priya Shankar")).isFalse();
        assertThat(VoiceTools.nameMatches("Mona Iyer", "Meena Iyer")).isFalse();     // short names match exactly
        assertThat(VoiceTools.nameMatches("Mani Iyer", "Meena Iyer")).isFalse();
        assertThat(VoiceTools.nameMatches("Kumari Devi", "Kumar Devi")).isFalse();     // a final vowel: another person
        assertThat(VoiceTools.nameMatches("Kamala Rao", "Kamal Rao")).isFalse();
        assertThat(VoiceTools.nameMatches("Kumaridevi", "Kumar Devi")).isFalse();      // joined up, same rules
        assertThat(VoiceTools.nameMatches("Monaiyer", "Meena Iyer")).isFalse();
        assertThat(VoiceTools.nameMatches("Suresh", "Suresh Kumar")).isFalse();
        assertThat(VoiceTools.nameMatches("Kumar Suresh", "Suresh Kumar Reddy")).isFalse();
        assertThat(VoiceTools.nameMatches("there is no syringe here", "Suresh Kumar")).isFalse();
        assertThat(VoiceTools.nameMatches("pre-eventkadish", "Priya Venkatesh")).isFalse();
        assertThat(VoiceTools.nameMatches("Lakshmi", "Lakshmi Narayanan")).isFalse();
    }

    @Test
    void scriptPlaceholdersAreFilledAndUnknownOnesDropped() {
        assertThat(VoiceScriptService.render("Hello {{first_name}} from {{company_name}}. {{something_else}}",
                Map.of("first_name", "Priya", "company_name", "Acme"))).isEqualTo("Hello Priya from Acme.");
        assertThat(VoiceScriptService.render("Am I speaking with {{first_name}}?", Map.of("first_name", "")))
                .isEqualTo("Am I speaking with?");
    }

    @Test
    void callersMayGiveAReferenceTheWayTheyWouldSayIt() {
        FieldDefinition ref = new FieldDefinition("customer_id", "Customer ID", FieldDefinition.Type.TEXT);
        assertThat(VoiceTools.answerMatches(ref, "CUST-10231", "cust 10231")).isTrue();
        assertThat(VoiceTools.answerMatches(ref, "CUST-10231", "10231")).isTrue();
        assertThat(VoiceTools.answerMatches(ref, "CUST-10231", "0231")).isTrue();
        assertThat(VoiceTools.answerMatches(ref, "CUST-10231", "10232")).isFalse();
        assertThat(VoiceTools.answerMatches(ref, "CUST-10231", "231")).isFalse();
        assertThat(VoiceTools.answerMatches(ref, "CUST-10231", "")).isFalse();
        FieldDefinition dob = new FieldDefinition("dob", "Date of birth", FieldDefinition.Type.DATE);
        assertThat(VoiceTools.answerMatches(dob, "2001-04-09", "9/4/2001")).isTrue();
    }

    @Test
    void spreadsheetValuesAreNormalisedWhateverTheirFormat() {
        assertThat(FieldService.normalise(FieldDefinition.Type.AMOUNT, "₹ 12,500.00")).isEqualTo("12500");
        assertThat(FieldService.normalise(FieldDefinition.Type.AMOUNT, "Rs. 1,250.50")).isEqualTo("1250.5");
        assertThat(FieldService.normalise(FieldDefinition.Type.DATE, "05/10/2026")).isEqualTo("2026-10-05");
        assertThat(FieldService.normalise(FieldDefinition.Type.DATE, "5-Oct-2026")).isEqualTo("2026-10-05");
        assertThat(FieldService.normalise(FieldDefinition.Type.DATE, "2026-10-05 00:00:00")).isEqualTo("2026-10-05");
        assertThatThrownBy(() -> FieldService.normalise(FieldDefinition.Type.DATE, "next Friday"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> FieldService.normalise(FieldDefinition.Type.NUMBER, "twelve"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(FieldService.keyOf("Amount Due (₹)")).isEqualTo("amount_due");
        assertThat(FieldService.keyOf("  Customer-ID ")).isEqualTo("customer_id");
    }

    @Test
    void importGuessesWhatEachColumnHolds() {
        Map<String, FieldDefinition> none = Map.of();
        assertThat(ImportService.guess("Customer Name", List.of(), none)).isEqualTo("name");
        assertThat(ImportService.guess("Mobile No", List.of(), none)).isEqualTo("phone");
        assertThat(ImportService.guess("Alternate Phone", List.of(), none)).isEqualTo("alt_phone");
        assertThat(ImportService.guess("E-mail", List.of(), none)).isEqualTo("email");
        assertThat(ImportService.guess("Remarks", List.of(), none)).isEqualTo("notes");
        assertThat(ImportService.guess("Amount Due", List.of("5,000", "12000"), none)).isEqualTo("new:AMOUNT");
        assertThat(ImportService.guess("Due Date", List.of("05/10/2026", "2026-11-01"), none)).isEqualTo("new:DATE");
        assertThat(ImportService.guess("Customer ID", List.of("10234", "10235"), none)).isEqualTo("new:TEXT");
        assertThat(ImportService.guess("Plan", List.of("Gold", "Silver"), none)).isEqualTo("new:TEXT");
        assertThat(ImportService.guess("Visits", List.of("3", "7"), none)).isEqualTo("new:NUMBER");
    }

    @Test
    void campaignFieldConditions() {
        LocalDate today = LocalDate.of(2026, 10, 5);
        assertThat(ContactFacts.fieldMatches(FieldOp.DUE_WITHIN_DAYS, "2026-10-08", "3", today)).isTrue();
        assertThat(ContactFacts.fieldMatches(FieldOp.DUE_WITHIN_DAYS, "2026-10-09", "3", today)).isFalse();
        assertThat(ContactFacts.fieldMatches(FieldOp.DUE_WITHIN_DAYS, "2026-10-04", "3", today)).isFalse();
        assertThat(ContactFacts.fieldMatches(FieldOp.OVERDUE, "2026-10-04", null, today)).isTrue();
        assertThat(ContactFacts.fieldMatches(FieldOp.OVERDUE, "2026-10-05", null, today)).isFalse();
        assertThat(ContactFacts.fieldMatches(FieldOp.GREATER_THAN, "5000", "0", today)).isTrue();
        assertThat(ContactFacts.fieldMatches(FieldOp.GREATER_THAN, "0", "0", today)).isFalse();
        assertThat(ContactFacts.fieldMatches(FieldOp.EQUALS, "Gold", "gold", today)).isTrue();
        assertThat(ContactFacts.fieldMatches(FieldOp.NOT_EMPTY, "x", null, today)).isTrue();
        assertThat(ContactFacts.fieldMatches(FieldOp.NOT_EMPTY, null, null, today)).isFalse();
        // A value of the wrong kind never matches rather than failing the whole campaign.
        assertThat(ContactFacts.fieldMatches(FieldOp.OVERDUE, "soon", null, today)).isFalse();
    }

    @Test
    void percentilesAndPercentages() {
        assertThat(VoiceQueryService.percentile(List.of(), 95)).isNull();
        assertThat(VoiceQueryService.percentile(List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100), 50)).isEqualTo(50);
        assertThat(VoiceQueryService.percentile(List.of(10, 20, 30, 40, 50, 60, 70, 80, 90, 100), 95)).isEqualTo(100);
        assertThat(VoiceQueryService.percent(1, 3)).isEqualTo(33);
        assertThat(VoiceQueryService.percent(5, 0)).isZero();
    }

    @Test
    void phoneNumbersAreMaskedAndSentAsE164() {
        assertThat(HandoffService.mask("9876543210")).isEqualTo("******3210");
        assertThat(VoiceCallService.e164("9876543210")).isEqualTo("+919876543210");
        assertThat(VoiceTools.clean("line one\n\tline two\u0007")).isEqualTo("line one line two");
    }

    private static VoiceProperties props(String secret) {
        return new VoiceProperties(true, "simulated", secret, 2500, NINE, EIGHT_PM, 2, 120, 24, 365, "m", "m", "",
                LocalTime.of(9, 30), LocalTime.of(18, 30), BigDecimal.TEN, BigDecimal.valueOf(60000), "data/recordings");
    }
}
