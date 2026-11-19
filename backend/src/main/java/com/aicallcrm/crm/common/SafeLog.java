package com.aicallcrm.crm.common;

import java.sql.SQLException;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Describes an exception for the server log without the personal data a database error carries. PostgreSQL's error
 * text holds the values themselves ("Key (phone)=(+91...) already exists"; for a missing value "Failing row contains
 * (...)", the whole row with name and number), and H2's does too. Server logs are kept outside the CRM and an erasure
 * never reaches them (compliance checklist 2.1), so for a database error only the exception's kind, the SQL state and
 * the constraint's name are logged: enough to find the cause, nothing about the person. Every other exception is
 * described as {@link Throwable#toString()}. Hibernate's own log of the same error is switched off in
 * application.properties ({@code org.hibernate.orm.jdbc.error}).
 */
public final class SafeLog {

    private SafeLog() {
    }

    /** A constraint's name: letters, digits, _ and . only, so no value can come with it. */
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.]+");

    public static String describe(Throwable e) {
        if (e == null) {
            return "null";
        }
        SQLException sql = null;
        String constraint = null;
        Map<Throwable, Boolean> seen = new IdentityHashMap<>();
        for (Throwable t = e; t != null && seen.put(t, true) == null; t = t.getCause()) {
            if (sql == null && t instanceof SQLException s) {
                sql = s;
            }
            if (constraint == null && t instanceof org.hibernate.exception.ConstraintViolationException c) {
                constraint = c.getConstraintName();
            }
        }
        if (sql == null) {
            return e.toString();
        }
        StringBuilder out = new StringBuilder(e.getClass().getSimpleName()).append(" (SQL state ")
                .append(sql.getSQLState() == null ? "unknown" : sql.getSQLState());
        Matcher m = NAME.matcher(constraint == null ? "" : constraint);
        if (m.lookingAt()) {
            out.append(", constraint ").append(m.group());
        }
        return out.append(')').toString();
    }
}
