package com.aicallcrm.crm.voice;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.LocalTime;

import org.junit.jupiter.api.Test;

/**
 * The call-back time promised for what the caller asked (CallbackTime), with the desk open 9:30 AM to 6:30 PM,
 * Monday to Saturday. "Now" is Monday 16 November 2026, 11:00 AM in India unless a test says otherwise.
 */
class CallbackTimeTest {

    private static final LocalTime OPEN = LocalTime.of(9, 30);
    private static final LocalTime CLOSE = LocalTime.of(18, 30);
    private static final Instant MONDAY_11 = ist(2026, 11, 16, 11, 0);
    /** The usual promise at that moment: two hours of desk time. */
    private static final Instant USUAL = ist(2026, 11, 16, 13, 0);

    private static Instant ist(int y, int mo, int d, int h, int mi) {
        return LocalDateTime.of(y, mo, d, h, mi).atZone(ContactFacts.IST).toInstant();
    }

    private static CallbackTime.Promise asked(String words) {
        return asked(words, MONDAY_11);
    }

    private static CallbackTime.Promise asked(String words, Instant now) {
        return CallbackTime.promise(words, now, OPEN, CLOSE, HandoffService.plusDeskTime(now, Duration.ofHours(2), OPEN, CLOSE));
    }

    private static void at(String words, Instant due) {
        CallbackTime.Promise p = asked(words);
        assertThat(p.due()).as(words).isEqualTo(due);
        assertThat(p.note()).as(words).isNull();
        assertThat(p.when()).as(words).startsWith("on ");
    }

    private static void usual(String words) {
        CallbackTime.Promise p = asked(words);
        assertThat(p.due()).as(words).isEqualTo(USUAL);
        assertThat(p.message()).as(words).isEqualTo("A member of our team will call you back by Monday 16 November, 1:00 PM.");
    }

    @Test
    void theTimeTheCallerAskedForIsTheTimePromised() {
        CallbackTime.Promise p = asked("Caller said: can you call me back at 4 pm?");
        assertThat(p.due()).isEqualTo(ist(2026, 11, 16, 16, 0));
        assertThat(p.message()).isEqualTo("A member of our team will call you back on Monday 16 November at 4:00 PM.");
        assertThat(p.slot()).isEqualTo("Monday 16 November, 4:00 PM");
        for (String same : new String[] {"4 pm", "4pm", "4 p.m.", "4 P.M.", "16:00", "at 4", "4 o'clock", "4 o’clock",
                "around 4", "Good morning, please call me at 4", "My ref is 4471 and I owe 2,500, call me at 4",
                "call me at 4 in the evening"}) {
            at(same, ist(2026, 11, 16, 16, 0));
        }
        at("4.30 pm", ist(2026, 11, 16, 16, 30));
        at("at 4:30", ist(2026, 11, 16, 16, 30));
        at("tomorrow at 10:30 am", ist(2026, 11, 17, 10, 30));
        at("noon tomorrow", ist(2026, 11, 17, 12, 0));
        at("day after tomorrow at 3 pm", ist(2026, 11, 18, 15, 0));
        at("Wednesday at 11", ist(2026, 11, 18, 11, 0));
        at("next Monday at 11 am", ist(2026, 11, 23, 11, 0));
        at("in 2 hours", ist(2026, 11, 16, 13, 0));
        at("in an hour and a half", ist(2026, 11, 16, 12, 30));
        at("after half an hour", ist(2026, 11, 16, 11, 30));
        at("in 40 minutes", ist(2026, 11, 16, 11, 40));
    }

    @Test
    void aTimeAlreadyGoneTodayIsTheNextWorkingDayAndTheDayIsSaid() {
        CallbackTime.Promise p = asked("call me at 10");
        assertThat(p.due()).isEqualTo(ist(2026, 11, 17, 10, 0));
        assertThat(p.message()).isEqualTo("A member of our team will call you back on Tuesday 17 November at 10:00 AM.");
        // Saturday evening: Sunday is shut, so Monday.
        assertThat(asked("at 4", ist(2026, 11, 21, 17, 0)).due()).isEqualTo(ist(2026, 11, 23, 16, 0));
        // "Today" said after the time: the usual promise, not tomorrow.
        usual("today at 10");
    }

    @Test
    void aPartOfTheDayIsPromisedAsThatWindow() {
        CallbackTime.Promise p = asked("tomorrow morning");
        assertThat(p.due()).isEqualTo(ist(2026, 11, 17, 12, 0));
        assertThat(p.message()).isEqualTo("A member of our team will call you back on Tuesday 17 November, between 9:30 AM and 12:00 PM.");
        assertThat(asked("in the evening").message())
                .isEqualTo("A member of our team will call you back on Monday 16 November, between 4:00 PM and 6:30 PM.");
        assertThat(asked("after 5").message())
                .isEqualTo("A member of our team will call you back on Monday 16 November, between 5:00 PM and 6:00 PM.");
        assertThat(asked("between 4 and 5 pm").message())
                .isEqualTo("A member of our team will call you back on Monday 16 November, between 4:00 PM and 5:00 PM.");
        assertThat(asked("4-5 pm").due()).isEqualTo(ist(2026, 11, 16, 17, 0));
        assertThat(asked("tomorrow between 11 and 1 pm").message())
                .isEqualTo("A member of our team will call you back on Tuesday 17 November, between 11:00 AM and 1:00 PM.");
        assertThat(asked("tomorrow").message())
                .isEqualTo("A member of our team will call you back on Tuesday 17 November, between 9:30 AM and 6:30 PM.");
        // Already under way, or "before": any time up to its end.
        assertThat(asked("this morning").message()).isEqualTo("A member of our team will call you back by Monday 16 November, 12:00 PM.");
        assertThat(asked("before 3 pm").message()).isEqualTo("A member of our team will call you back by Monday 16 November, 3:00 PM.");
        assertThat(asked("within 2 hours").message()).isEqualTo("A member of our team will call you back by Monday 16 November, 1:00 PM.");
        // The morning is over at 11:50 with no day given: tomorrow's.
        assertThat(asked("in the morning", ist(2026, 11, 16, 11, 50)).due()).isEqualTo(ist(2026, 11, 17, 12, 0));
    }

    @Test
    void outsideDeskHoursTheCallerIsToldTheHours() {
        for (String words : new String[] {"at 8 pm", "tonight", "Sunday at 4", "after 7 pm", "before 9", "in 9 hours"}) {
            CallbackTime.Promise p = asked(words);
            assertThat(p.due()).as(words).isEqualTo(USUAL);
            assertThat(p.message()).as(words).isEqualTo("Our team makes calls between 9:30 AM and 6:30 PM, Monday to Saturday, "
                    + "so a member of our team will call you back by Monday 16 November, 1:00 PM.");
        }
    }

    @Test
    void anythingUnclearGetsTheUsualPromise() {
        for (String words : new String[] {null, "", "soon", "later", "whenever", "next week", "on the 20th",
                "not tomorrow, call me on Wednesday", "I'm busy at 4", "don't call at 4, call at 5", "dont call me tomorrow",
                "tomorrow or Wednesday", "at 4 or at 5", "in the morning or the evening", "in 10 minutes", "today",
                "between 5 and 3 pm", "at 25", "13 pm", "at 4:75", "I owe 12.50", "sometime in the next 2 days"}) {
            usual(words);
        }
        // Too little notice to promise: 3:50 PM, asked for 4.
        Instant now = ist(2026, 11, 16, 15, 50);
        assertThat(asked("at 4", now).due()).isEqualTo(ist(2026, 11, 16, 17, 50));
    }

    @Test
    void twoHoursAreCountedInDeskTime() {
        Duration two = Duration.ofHours(2);
        assertThat(HandoffService.plusDeskTime(MONDAY_11, two, OPEN, CLOSE)).isEqualTo(ist(2026, 11, 16, 13, 0));
        assertThat(HandoffService.plusDeskTime(ist(2026, 11, 16, 16, 30), two, OPEN, CLOSE)).isEqualTo(ist(2026, 11, 16, 18, 30));
        assertThat(HandoffService.plusDeskTime(ist(2026, 11, 16, 17, 30), two, OPEN, CLOSE)).isEqualTo(ist(2026, 11, 17, 10, 30));
        assertThat(HandoffService.plusDeskTime(ist(2026, 11, 21, 18, 0), two, OPEN, CLOSE)).isEqualTo(ist(2026, 11, 23, 11, 0));
        assertThat(HandoffService.plusDeskTime(ist(2026, 11, 22, 12, 0), two, OPEN, CLOSE)).isEqualTo(ist(2026, 11, 23, 11, 30));
        assertThat(HandoffService.plusDeskTime(ist(2026, 11, 16, 20, 0), Duration.ofMinutes(30), OPEN, CLOSE))
                .isEqualTo(ist(2026, 11, 17, 10, 0));
    }
}
