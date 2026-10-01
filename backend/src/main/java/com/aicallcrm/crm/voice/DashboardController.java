package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.contact.ContactRepository;
import com.aicallcrm.crm.contact.ContactService;
import com.aicallcrm.crm.contact.ContactStatus;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.voice.Voice.CallStatus;
import com.aicallcrm.crm.voice.Voice.CallbackStatus;

/** The numbers on the home page. Agents see their own work; admins and managers see the whole company. */
@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {

    public record Summary(Map<String, Long> contactsByStatus, long totalContacts, long newThisWeek, int myFollowUpsDue,
                          int openCallbacks, int aiCallsToday, int aiCallsSimulatedToday) {
    }

    private final ContactRepository contacts;
    private final ContactService contactService;
    private final CallbackRequestRepository callbacks;
    private final VoiceCallRepository calls;

    public DashboardController(ContactRepository contacts, ContactService contactService,
                               CallbackRequestRepository callbacks, VoiceCallRepository calls) {
        this.contacts = contacts;
        this.contactService = contactService;
        this.callbacks = callbacks;
        this.calls = calls;
    }

    @GetMapping
    @Transactional(readOnly = true)
    public Summary summary() {
        CurrentUser me = CurrentUser.get();
        Map<String, Long> byStatus = new LinkedHashMap<>();
        for (ContactStatus s : ContactStatus.values()) {
            byStatus.put(s.name(), 0L);
        }
        long total = 0;
        if (me.seesAllContacts()) {
            for (Object[] row : contacts.countByStatus()) {
                byStatus.put(((ContactStatus) row[0]).name(), (Long) row[1]);
                total += (Long) row[1];
            }
        } else {
            for (ContactStatus s : ContactStatus.values()) {
                long n = contactService.search(new ContactService.Filter(null, s, me.id(), false, null, null), 0, 1)
                        .totalItems();
                byStatus.put(s.name(), n);
                total += n;
            }
        }
        Instant weekAgo = Instant.now().minus(7, ChronoUnit.DAYS);
        long newThisWeek = me.seesAllContacts() ? contacts.countByCreatedAtGreaterThanEqual(weekAgo) : 0;
        int followUps = contactService.myFollowUps(0).size();
        int openCallbacks = (int) callbacks.findByStatusInOrderByDueAtAsc(List.of(CallbackStatus.OPEN, CallbackStatus.ASSIGNED))
                .stream().filter(c -> me.seesAllContacts() || c.getAssignedTo() == null
                        || c.getAssignedTo().getId().equals(me.id())).count();
        Instant today = LocalDate.now(ContactFacts.IST).atStartOfDay(ContactFacts.IST).toInstant();
        List<VoiceCall> todays = calls.findByTestCallFalseAndCreatedAtGreaterThanEqual(today);
        int simulated = (int) todays.stream().filter(c -> c.getStatus() == CallStatus.SIMULATED).count();
        return new Summary(byStatus, total, newThisWeek, followUps, openCallbacks, todays.size() - simulated, simulated);
    }
}
