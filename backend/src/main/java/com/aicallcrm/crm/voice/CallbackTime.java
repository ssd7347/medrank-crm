package com.aicallcrm.crm.voice;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The call-back time to promise a caller, from the time they asked for in their own words: "4 pm", "tomorrow
 * morning", "Caller said: can you call me back at 4 pm?" (rehearsal of 16 Nov: asked for 4 pm, was told 9:05 PM).
 *
 * <p>Only a request read without doubt is promised: one day, one time (or one part of the day, or one "in two
 * hours"), nothing negated near it ("not tomorrow"), inside desk hours, Monday to Saturday, and at least
 * {@link #MIN_NOTICE} away. Anything else gets the usual "by" time, which the team can always keep; a request outside
 * desk hours is told the hours first. A time already gone today with no day given means the next working day, and the
 * day is always spoken, so the caller hears which one.
 */
final class CallbackTime {

    /** Less notice than this and the team may not see the task in time: the usual "by" time instead. */
    static final Duration MIN_NOTICE = Duration.ofMinutes(15);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEEE d MMMM", Locale.ENGLISH);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);

    /**
     * {@code when} follows "will call you back" ("on Tuesday 17 November at 4:00 PM"); {@code slot} is the tool's
     * {@code confirmed_slot}; {@code note}, if any, comes first (the desk hours, when the caller asked for a time
     * outside them).
     */
    record Promise(Instant due, String when, String slot, String note) {
        String message() {
            String promise = "a member of our team will call you back " + when + ".";
            return note == null ? "A" + promise.substring(1) : note + promise;
        }
    }

    private CallbackTime() {
    }

    /** The usual promise: by this time. */
    static Promise by(Instant due) {
        return new Promise(due, "by " + ContactFacts.spokenTime(due), ContactFacts.spokenTime(due), null);
    }

    /**
     * @param asked    what the caller asked for, in their words (may be null)
     * @param fallback the usual "by" time, kept when the request cannot be promised
     */
    static Promise promise(String asked, Instant now, LocalTime open, LocalTime close, Instant fallback) {
        ZonedDateTime here = now.atZone(ContactFacts.IST);
        Asked a = read(asked, here.toLocalDate());
        if (a == null) {
            return by(fallback);
        }
        Promise outside = new Promise(fallback, "by " + ContactFacts.spokenTime(fallback),
                ContactFacts.spokenTime(fallback), "Our team makes calls between " + time(open) + " and " + time(close)
                + ", Monday to Saturday, so ");
        if (a.in != null) {
            Instant at = roundUp(now.plus(a.in));
            if (a.in.compareTo(MIN_NOTICE) < 0) {
                return by(fallback);
            }
            if (!inHours(at, open, close)) {
                return outside;
            }
            return a.byOnly ? by(at) : at(at);
        }
        LocalDate day = a.day != null ? a.day : here.toLocalDate();
        for (int tries = 0; tries < 2; tries++) {
            if (day.getDayOfWeek() == DayOfWeek.SUNDAY) {
                return outside;
            }
            if (a.exact) {
                if (a.from.isBefore(open) || !a.from.isBefore(close)) {
                    return outside;
                }
                Instant at = day.atTime(a.from).atZone(ContactFacts.IST).toInstant();
                if (at.isBefore(now) && a.day == null && tries == 0) {
                    day = nextWorkingDay(day);
                    continue;
                }
                return at.isBefore(now.plus(MIN_NOTICE)) ? by(fallback) : at(at);
            }
            LocalTime lo = a.from == null || a.from.isBefore(open) ? open : a.from;
            LocalTime hi = a.to.isAfter(close) ? close : a.to;
            if (Duration.between(lo, hi).compareTo(MIN_NOTICE) < 0) {
                return outside;
            }
            Instant start = day.atTime(lo).atZone(ContactFacts.IST).toInstant();
            Instant end = day.atTime(hi).atZone(ContactFacts.IST).toInstant();
            if (end.isBefore(now.plus(MIN_NOTICE))) {
                if (a.day == null && tries == 0) {
                    day = nextWorkingDay(day);
                    continue;
                }
                return by(fallback);
            }
            if (a.byOnly || !start.isAfter(now)) {
                return by(end);
            }
            String slot = DAY.format(day) + ", between " + time(lo) + " and " + time(hi);
            return new Promise(end, "on " + slot, slot, null);
        }
        return by(fallback);
    }

    private static Promise at(Instant at) {
        ZonedDateTime z = at.atZone(ContactFacts.IST);
        return new Promise(at, "on " + DAY.format(z) + " at " + TIME.format(z), ContactFacts.spokenTime(at), null);
    }

    private static boolean inHours(Instant at, LocalTime open, LocalTime close) {
        ZonedDateTime z = at.atZone(ContactFacts.IST);
        return z.getDayOfWeek() != DayOfWeek.SUNDAY && !z.toLocalTime().isBefore(open) && z.toLocalTime().isBefore(close);
    }

    private static LocalDate nextWorkingDay(LocalDate day) {
        LocalDate next = day.plusDays(1);
        return next.getDayOfWeek() == DayOfWeek.SUNDAY ? next.plusDays(1) : next;
    }

    /** To the next five minutes: "in two hours" said at 2:03 is 4:05. */
    private static Instant roundUp(Instant at) {
        ZonedDateTime z = at.atZone(ContactFacts.IST).truncatedTo(ChronoUnit.MINUTES);
        int over = z.getMinute() % 5;
        return (over == 0 && z.toInstant().equals(at) ? z : z.plusMinutes(5 - over)).toInstant();
    }

    private static String time(LocalTime t) {
        return TIME.format(t);
    }

    // ------------------------------------------------------------------ reading the request

    /**
     * What was asked: a day (null: none given), and either a relative time ({@code in}), an exact time ({@code exact},
     * {@code from}), or a window {@code from}–{@code to} ({@code from} null: any time before {@code to}).
     * {@code byOnly}: "before 4", "within an hour": any time up to it.
     */
    private record Asked(LocalDate day, LocalTime from, LocalTime to, boolean exact, boolean byOnly, Duration in) {
    }

    private static final String WEEKDAYS = "monday|tuesday|wednesday|thursday|friday|saturday|sunday";
    private static final String TOMORROW = "tomorrow|tomorow|tommorow|tommorrow|tmrw|tmr";
    /** "not tomorrow", "don't call me at 4", "busy at 4": a day or a time within three words of one of these. */
    private static final Pattern NEGATED = Pattern.compile("(?:\\b(?:not|never|except|dont|cant|cannot|wont|busy)|n't)"
            + "\\s+(?:\\S+\\s+){0,3}?"
            + "(?:today|" + TOMORROW + "|tonight|morning|afternoon|evening|night|" + WEEKDAYS + "|\\d)");
    private static final Pattern GREETING = Pattern.compile("\\bgood\\s+(?:morning|afternoon|evening|night)\\b");
    private static final Pattern DAY_AFTER = Pattern.compile("\\bday\\s+after\\s+(?:" + TOMORROW + ")\\b");
    private static final Pattern DAY_WORD = Pattern.compile("\\b(?:(" + TOMORROW + ")|(today|tonight)|(next\\s+)?("
            + WEEKDAYS + ")s?)\\b");
    private static final Pattern THIS_PART = Pattern.compile("\\bthis\\s+(?:morning|afternoon|evening)\\b");
    private static final Pattern PART = Pattern.compile("\\b(morning|afternoon|evening|night|tonight)\\b");
    private static final Pattern RELATIVE = Pattern.compile("\\b(in|after|within)\\s+(?:(?:(an?|one|\\d{1,2}(?:\\.5)?)"
            + "\\s*(?:hours?|hrs?)(\\s+and\\s+a\\s+half)?)|(half\\s+an?\\s+hour)|(\\d{1,3})\\s*(?:minutes?|mins?))\\b");
    private static final String T = "(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm|oclock)?";
    private static final Pattern BETWEEN = Pattern.compile("\\b(?:between|from)\\s+" + T + "\\s*(?:and|to|till|until|-)\\s*"
            + T + "\\b");
    /** "4-5 pm", "4 to 5 pm": a range written without "between", so the second time says am or pm. */
    private static final Pattern RANGE = Pattern.compile("\\b" + T + "\\s*(?:-|to)\\s*(\\d{1,2})(?:[:.](\\d{2}))?\\s*(am|pm)\\b");
    private static final Pattern TIME_SAID = Pattern.compile("(?:\\b(at|after|before|by|around|about|till|until|untill)\\s+)?"
            + "\\b(\\d{1,2})(?:([:.])(\\d{2}))?(?:\\s*(am|pm|oclock))?\\b(?!\\s*(?:hours?|hrs?|minutes?|mins?|days?|%))");

    /** Null when the request is missing, unclear, or says two different things. */
    private static Asked read(String asked, LocalDate today) {
        if (asked == null || asked.isBlank()) {
            return null;
        }
        String s = " " + asked.toLowerCase(Locale.ROOT).replaceAll("[‘’`]", "'")
                .replaceAll("\\ba\\.?\\s?m\\b\\.?", "am").replaceAll("\\bp\\.?\\s?m\\b\\.?", "pm")
                .replaceAll("\\bo'?\\s?clock\\b|\\bbaje\\b", "oclock")
                .replaceAll("\\b(?:12\\s*)?noon\\b|\\bmidday\\b", "12:00 pm") + " ";
        if (NEGATED.matcher(s).find()) {
            return null;
        }
        s = GREETING.matcher(s).replaceAll(" ");

        Set<LocalDate> days = new LinkedHashSet<>();
        Matcher m = DAY_AFTER.matcher(s);
        while (m.find()) {
            days.add(today.plusDays(2));
        }
        s = m.replaceAll(" ");
        m = THIS_PART.matcher(s);
        if (m.find()) {
            days.add(today);
        }
        m = DAY_WORD.matcher(s);
        while (m.find()) {
            if (m.group(1) != null) {
                days.add(today.plusDays(1));
            } else if (m.group(2) != null) {
                days.add(today);
            } else {
                DayOfWeek dow = DayOfWeek.valueOf(m.group(4).toUpperCase(Locale.ROOT));
                days.add(m.group(3) != null ? today.with(TemporalAdjusters.next(dow))
                        : today.with(TemporalAdjusters.nextOrSame(dow)));
            }
        }
        if (days.size() > 1) {
            return null;
        }
        LocalDate day = days.isEmpty() ? null : days.iterator().next();

        Duration in = null;
        boolean within = false;
        m = RELATIVE.matcher(s);
        int relatives = 0;
        while (m.find()) {
            relatives++;
            within = "within".equals(m.group(1));
            if (m.group(4) != null) {
                in = Duration.ofMinutes(30);
            } else if (m.group(5) != null) {
                in = Duration.ofMinutes(Long.parseLong(m.group(5)));
            } else {
                String n = m.group(2);
                double hours = n.startsWith("a") || n.equals("one") ? 1 : Double.parseDouble(n);
                in = Duration.ofMinutes(Math.round(hours * 60) + (m.group(3) != null ? 30 : 0));
            }
        }
        s = m.replaceAll(" ");

        List<LocalTime[]> windows = new ArrayList<>();
        for (Pattern range : List.of(BETWEEN, RANGE)) {
            m = range.matcher(s);
            while (m.find()) {
                LocalTime b = clock(m.group(4), m.group(5), m.group(6));
                LocalTime a = clock(m.group(1), m.group(2), m.group(3) != null ? m.group(3) : m.group(6));
                if (a != null && b != null && m.group(3) == null && a.isAfter(b) && a.getHour() >= 12) {
                    a = a.minusHours(12);          // "between 11 and 1 pm": 11 in the morning
                    if (a.getHour() < 8) {
                        return null;               // "between 5 and 3 pm": not 5 in the morning
                    }
                }
                if (a == null || b == null || !a.isBefore(b)) {
                    return null;
                }
                windows.add(new LocalTime[] {a, b});
            }
            s = m.replaceAll(" ");
        }

        List<Asked> times = new ArrayList<>();
        m = TIME_SAID.matcher(s);
        while (m.find()) {
            String cue = m.group(1);
            String sep = m.group(3);
            String mer = m.group(5);
            // A bare number is a time only after "at", "by" and the like, or written as one ("4 pm", "16:30").
            if (cue == null && mer == null && !":".equals(sep)) {
                continue;
            }
            LocalTime t = clock(m.group(2), m.group(4), mer);
            if (t == null) {
                return null;
            }
            switch (cue == null ? "at" : cue) {
                case "before", "by", "till", "until", "untill" -> times.add(new Asked(day, null, t, false, true, null));
                case "after" -> times.add(new Asked(day, t, t.plusHours(1).isBefore(t) ? LocalTime.MAX : t.plusHours(1),
                        false, false, null));
                default -> times.add(new Asked(day, t, t, true, false, null));
            }
        }

        Set<String> parts = new LinkedHashSet<>();
        m = PART.matcher(s);
        while (m.find()) {
            parts.add(m.group(1).equals("tonight") ? "night" : m.group(1));
        }

        if (relatives > 0) {
            return relatives == 1 && day == null && windows.isEmpty() && times.isEmpty() && parts.isEmpty()
                    ? new Asked(null, null, null, false, within, in) : null;
        }
        if (windows.size() + times.size() > 1) {
            return null;
        }
        if (!windows.isEmpty()) {
            return new Asked(day, windows.get(0)[0], windows.get(0)[1], false, false, null);
        }
        if (!times.isEmpty()) {
            return times.get(0);       // a time given says more than "in the evening" next to it
        }
        if (parts.size() == 1) {
            return switch (parts.iterator().next()) {
                case "morning" -> new Asked(day, LocalTime.of(6, 0), LocalTime.NOON, false, false, null);
                case "afternoon" -> new Asked(day, LocalTime.NOON, LocalTime.of(16, 0), false, false, null);
                case "evening" -> new Asked(day, LocalTime.of(16, 0), LocalTime.of(19, 0), false, false, null);
                default -> new Asked(day, LocalTime.of(19, 0), LocalTime.of(22, 0), false, false, null);
            };
        }
        if (day != null && day.isAfter(today)) {
            return new Asked(day, LocalTime.MIN, LocalTime.MAX, false, false, null);   // "tomorrow": any time that day
        }
        return null;                   // "today" alone, or nothing readable: the usual time
    }

    /**
     * "4 pm" is 16:00; "16:30" as written; a bare "4" after "at" is the afternoon (1 to 7), "9" the morning (8 to 11),
     * "12" noon. Null if it is not a time of day.
     */
    static LocalTime clock(String h, String m, String mer) {
        int hour = Integer.parseInt(h);
        int minute = m == null ? 0 : Integer.parseInt(m);
        if (minute > 59) {
            return null;
        }
        if ("am".equals(mer) || "pm".equals(mer)) {
            if (hour < 1 || hour > 12) {
                return null;
            }
            hour = hour % 12 + ("pm".equals(mer) ? 12 : 0);
        } else if (hour > 23) {
            return null;
        } else if (hour >= 1 && hour <= 7) {
            hour += 12;
        }
        return LocalTime.of(hour, minute);
    }
}
