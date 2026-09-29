package com.mbbscrm.crm.alert;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Sends queued messages, urgent first. Runs immediately (asynchronously) after an urgent message is
 * committed, and on a periodic sweep that also retries failures. A lock ensures only one pass runs at a
 * time, so a message is never sent twice by this instance. (If the backend is ever scaled to several
 * instances, replace the lock with a row-claiming query.)
 */
@Component
public class MessageDispatcher {

    private static final Logger log = LoggerFactory.getLogger(MessageDispatcher.class);
    private static final int BATCH = 50;

    private final OutboundMessageRepository messages;
    private final MessageSender sender;
    private final TransactionTemplate tx;
    private final ReentrantLock lock = new ReentrantLock();
    private final AtomicBoolean rerun = new AtomicBoolean();

    public MessageDispatcher(OutboundMessageRepository messages, List<MessageSender> senders, TransactionTemplate tx) {
        this.messages = messages;
        // Prefer a real provider; fall back to the simulated sender.
        this.sender = senders.stream().filter(s -> !(s instanceof SimulatedMessageSender)).findFirst()
                .orElseGet(() -> senders.stream().filter(s -> s instanceof SimulatedMessageSender).findFirst()
                        .orElseThrow());
        this.tx = tx;
        log.info("Outbound messages will use {}", sender.getClass().getSimpleName());
    }

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onUrgentQueued(AlertService.UrgentQueued event) {
        dispatchPending();
    }

    @Scheduled(initialDelayString = "${app.alerts.dispatch-interval:PT1M}",
            fixedDelayString = "${app.alerts.dispatch-interval:PT1M}")
    public void sweep() {
        dispatchPending();
    }

    /** Sends everything queued. Returns how many messages were attempted. */
    public int dispatchPending() {
        if (!lock.tryLock()) {
            rerun.set(true);
            return 0;
        }
        int attempted = 0;
        try {
            do {
                rerun.set(false);
                List<Long> ids;
                do {
                    ids = tx.execute(s -> messages.findQueued(PageRequest.of(0, BATCH)).stream()
                            .map(OutboundMessage::getId).toList());
                    for (Long id : ids) {
                        tx.executeWithoutResult(s -> sendOne(id));
                        attempted++;
                    }
                } while (ids != null && ids.size() == BATCH);
            } while (rerun.get());
        } finally {
            lock.unlock();
        }
        return attempted;
    }

    private void sendOne(Long id) {
        OutboundMessage m = messages.findById(id).orElse(null);
        if (m == null || m.getStatus() != MessageStatus.QUEUED) {
            return;
        }
        try {
            MessageSender.Result r = sender.send(m.getChannel(), m.getRecipient(), m.getBody());
            if (r.delivered()) {
                m.markDelivered(r.simulated() ? MessageStatus.SIMULATED : MessageStatus.SENT, r.providerRef());
            } else {
                m.markFailedAttempt(r.error());
            }
        } catch (RuntimeException e) {
            log.warn("Sending message {} failed: {}", id, e.getMessage());
            m.markFailedAttempt(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }
}
