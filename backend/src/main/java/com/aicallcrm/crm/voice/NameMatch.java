package com.aicallcrm.crm.voice;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Does the name a caller gave match the registered name, allowing for how the ear (Whisper) spells Indian names?
 *
 * <p>On a phone line Whisper writes the right name in another spelling: Suresh as "Surish" or "Sirish", Irfan as
 * "Erfan", Lakshmi Narayanan as "Laxminarayanan" (ear check, 11 Nov 2026). An exact match would fail every such
 * caller. So:
 * <ul>
 *   <li>spellings that sound alike are the same: ksh/ks and x, ph and f, bh, dh, th, gh, kh, jh as b, d, t, g, k, j,
 *       sh as s, w as v, ee as i, oo as u, an h after the first letter, double letters;</li>
 *   <li>after that, vowels may differ (Suresh, Surish, Sirish; Irfan, Erfan) but every consonant must be the same and
 *       in order (Ramesh is not Rajesh, Suresh is not Sudesh), the name must end the same way (Kumar is not Kumari,
 *       Kamal is not Kamala), and only in names with at least three consonants: short names share too few (Meena, Mona
 *       and Mani must match exactly);</li>
 *   <li>a name heard as one word, or split differently ("Laxminarayanan"), must cut back into the registered words,
 *       each piece matching by the rules above.</li>
 * </ul>
 * Every word of the registered name must be matched, as before. What this gives up: a few different names with the
 * same consonants and ending are taken as one (of 123 common first names: Karan and Kiran, Sunil and Sonal, Kamal and
 * Komal). The identity question (customer ID, date of birth...) still has to
 * be answered exactly, so the name alone never verifies anyone.
 */
final class NameMatch {

    private NameMatch() {
    }

    static boolean matches(String said, String registered) {
        List<String> expected = spelled(registered);
        List<String> given = spelled(said);
        if (expected.isEmpty() || given.isEmpty()) {
            return false;
        }
        if (expected.stream().allMatch(e -> given.stream().anyMatch(g -> alike(e, g)))) {
            return true;
        }
        // Heard split differently ("Laxminarayanan", "Laxmina Rianan"): some run of the caller's words, joined up,
        // must cut back into the registered words in order, each piece matching by the same rules as a word.
        for (int i = 0; i < given.size(); i++) {
            StringBuilder joined = new StringBuilder();
            for (int j = i; j < given.size() && j < i + MAX_JOINED; j++) {
                joined.append(given.get(j));
                if (cutsInto(expected, 0, joined.toString())) {
                    return true;
                }
            }
        }
        return false;
    }

    private static final int MAX_JOINED = 4;

    private static boolean cutsInto(List<String> expected, int k, String heard) {
        if (k == expected.size() - 1) {
            return alike(expected.get(k), heard);
        }
        for (int cut = 1; cut < heard.length(); cut++) {
            if (alike(expected.get(k), heard.substring(0, cut)) && cutsInto(expected, k + 1, heard.substring(cut))) {
                return true;
            }
        }
        return false;
    }

    /** Words of two letters or more, in a sound-alike spelling (initials are dropped, as before). */
    static List<String> spelled(String name) {
        List<String> out = new ArrayList<>();
        if (name != null) {
            for (String w : name.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{Nd}]+")) {
                if (w.length() > 1) {
                    out.add(spell(w));
                }
            }
        }
        return out;
    }

    static String spell(String word) {
        String s = word.toLowerCase(Locale.ROOT)
                .replace("ksh", "x").replace("ks", "x")
                .replace("ph", "f").replace("bh", "b").replace("dh", "d").replace("th", "t").replace("gh", "g")
                .replace("kh", "k").replace("jh", "j").replace("sh", "s").replace("ch", "c").replace("ck", "k")
                .replace('q', 'k').replace('w', 'v');
        if (s.length() > 1) {
            s = s.charAt(0) + s.substring(1).replace("h", "");
        }
        return collapse(s.replace("ee", "i").replace("oo", "u"));
    }

    static boolean alike(String a, String b) {
        a = collapse(a);
        b = collapse(b);
        if (a.equals(b)) {
            return true;
        }
        String ca = consonants(a);
        // Same ending too: a final vowel often makes another person's name (Kumar and Kumari, Kamal and Kamala).
        return ca.length() >= 3 && ca.equals(consonants(b)) && Math.abs(a.length() - b.length()) <= 2
                && endsInVowel(a) == endsInVowel(b);
    }

    private static boolean endsInVowel(String s) {
        return !s.isEmpty() && "aeiouy".indexOf(s.charAt(s.length() - 1)) >= 0;
    }

    private static String consonants(String s) {
        return s.replaceAll("[aeiouy]", "");
    }

    private static String collapse(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            if (i == 0 || s.charAt(i) != s.charAt(i - 1)) {
                b.append(s.charAt(i));
            }
        }
        return b.toString();
    }
}
