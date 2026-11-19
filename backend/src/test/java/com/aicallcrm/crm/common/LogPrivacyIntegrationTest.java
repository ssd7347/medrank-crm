package com.aicallcrm.crm.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import com.aicallcrm.crm.contact.Contact;
import com.aicallcrm.crm.contact.ContactRepository;

/**
 * Server logs are a copy of personal data that an erasure never reaches (compliance checklist 2.1), so a database
 * error must not put a customer's number or name in them. PostgreSQL's own error text does: "Key (phone)=(...)
 * already exists" and, for a missing value, "Failing row contains (...)" with the whole row; so does H2's. The log
 * keeps what a developer needs (the SQL state and the constraint) and nothing else. Run on H2 and on PostgreSQL.
 *
 * <p>The errors are real ones from the database, given to the CRM's own error handler as a request would. The
 * context is the one the other integration tests share (an extra one ran PostgreSQL out of connections).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class LogPrivacyIntegrationTest {

    private static final AtomicLong SEQ = new AtomicLong(System.nanoTime() % 10_000_000L);

    @Autowired
    ContactRepository contacts;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    GlobalExceptionHandler handler;

    static String phone() {
        return "+9170" + String.format("%08d", SEQ.incrementAndGet() % 100_000_000L);
    }

    static Contact contact(String name, String phone) {
        Contact c = new Contact();
        c.setFullName(name);
        c.setPhone(phone);
        return c;
    }

    @Test
    void aDuplicateNumberIsLoggedByItsConstraintNotByTheNumber(CapturedOutput output) {
        String phone = phone();
        contacts.saveAndFlush(contact("Wilhelmina Logsafe", phone));
        int before = output.length();

        // Through JPA, where Hibernate logs failed statements itself.
        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
                () -> contacts.saveAndFlush(contact("Wilhelmina Logsafe", phone)));
        assertThat(handler.handleIntegrity(e).getStatus()).isEqualTo(HttpStatus.CONFLICT.value());

        String logged = output.toString().substring(before);
        assertThat(logged).contains("Data integrity violation");
        assertThat(logged.toLowerCase()).contains("uq_contact_phone");
        assertThat(logged).doesNotContain(phone.substring(1)).doesNotContain("Logsafe");
    }

    @Test
    void aRowThatCannotBeSavedIsNotWrittenToTheLog(CapturedOutput output) {
        String phone = phone();
        int before = output.length();

        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
                () -> jdbc.update("insert into contact (full_name, phone, status, created_at, updated_at)"
                        + " values (?, ?, null, current_timestamp, current_timestamp)", "Bartholomew Logsafe", phone));
        handler.handleIntegrity(e);

        String logged = output.toString().substring(before);
        assertThat(logged).contains("Data integrity violation").contains("23502");
        assertThat(logged).doesNotContain(phone.substring(1)).doesNotContain("Logsafe");
    }

    @Test
    void theDescriptionKeepsTheConstraintAndStateButNoValues() {
        String phone = phone();
        jdbc.update("insert into contact (full_name, phone, status, created_at, updated_at)"
                + " values (?, ?, 'NEW', current_timestamp, current_timestamp)", "Cornelius Logsafe", phone);
        DataIntegrityViolationException e = assertThrows(DataIntegrityViolationException.class,
                () -> contacts.saveAndFlush(contact("Cornelius Logsafe", phone)));
        // The raw message names the number (that is the leak this guards against)...
        assertThat(String.valueOf(e.getMostSpecificCause().getMessage())).contains(phone.substring(1));
        // ...the description does not.
        String safe = SafeLog.describe(e);
        assertThat(safe).startsWith("DataIntegrityViolationException").contains("23505");
        assertThat(safe.toLowerCase()).contains("uq_contact_phone");
        assertThat(safe).doesNotContain(phone.substring(1)).doesNotContain("Logsafe");
        // Anything else is described as before.
        assertThat(SafeLog.describe(new IllegalStateException("the brain timed out")))
                .isEqualTo("java.lang.IllegalStateException: the brain timed out");
    }
}
