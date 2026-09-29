package com.mbbscrm.crm.common;

/** Normalises Indian phone numbers so duplicate detection compares like with like. */
public final class Phones {

    private Phones() {
    }

    /** Strips spaces/dashes and a leading +91 / 91 / 0 from 10-digit Indian mobiles. */
    public static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String digits = raw.replaceAll("[^0-9+]", "");
        if (digits.startsWith("+91") && digits.length() == 13) {
            return digits.substring(3);
        }
        if (digits.startsWith("91") && digits.length() == 12) {
            return digits.substring(2);
        }
        if (digits.startsWith("0") && digits.length() == 11) {
            return digits.substring(1);
        }
        return digits;
    }
}
