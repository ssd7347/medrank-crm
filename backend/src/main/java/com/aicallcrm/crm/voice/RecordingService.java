package com.aicallcrm.crm.voice;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.user.AppUserRepository;

/**
 * Call recordings (decided 17 Nov 2026). Every real AI call whose opening line tells the person it may be recorded is
 * recorded by our speech engine, both voices, and sent here when the call ends. Each is one Opus audio file in a
 * folder on this server ({@code app.voice.recordings-dir}); {@code voice_call.recording_ref} is "file:" and its path in
 * that folder. Recordings go with the transcript after {@code app.voice.retention-days} (never fewer than 120), and when
 * the person is erased. Only the admin, and staff the admin has allowed ("Can listen to call recordings"), can play
 * one; each play is in the audit log. Test-console calls are never recorded.
 */
@Service
public class RecordingService {

    private static final Logger log = LoggerFactory.getLogger(RecordingService.class);

    static final String FILE = "file:";
    /** The largest recording taken: about 3 hours of speech at the engine's 16 kbit/s. */
    static final int MAX_BYTES = 25 * 1024 * 1024;
    private static final Pattern OWN_PATH = Pattern.compile("\\d{4}-\\d{2}/[0-9a-f-]{36}\\.ogg");
    private static final DateTimeFormatter MONTH = DateTimeFormatter.ofPattern("yyyy-MM", Locale.ROOT)
            .withZone(ZoneId.of("Asia/Kolkata"));

    private final VoiceCallRepository calls;
    private final AppUserRepository users;
    private final AuditService audit;
    private final VoiceProperties props;
    private final Path dir;

    public RecordingService(VoiceCallRepository calls, AppUserRepository users, AuditService audit,
                            VoiceProperties props) {
        this.calls = calls;
        this.users = users;
        this.audit = audit;
        this.props = props;
        this.dir = Path.of(props.recordingsDir()).toAbsolutePath().normalize();
    }

    /** True when an opening line tells the person the call may be recorded ("recorded", "recording"). */
    public static boolean announces(String openingLine) {
        return openingLine != null && VoiceScriptService.RECORDED.matcher(openingLine).find();
    }

    // ------------------------------------------------------------------ from the speech engine

    /**
     * The engine's recording of a call, as base64 Ogg Opus. Kept only for a real call that was to be recorded, has
     * none yet, has not been erased and is within the retention period. True if it was kept.
     */
    @Transactional
    public boolean receive(UUID callId, String base64) {
        VoiceCall call = calls.findById(callId).orElse(null);
        if (call == null || call.isTestCall() || !call.isRecordingAllowed() || call.getRecordingRef() != null
                || call.getPhone() == null || call.getPhone().isBlank()
                || call.getCreatedAt().isBefore(Instant.now().minus(Duration.ofDays(props.retentionDays())))) {
            return false;
        }
        byte[] audio;
        try {
            audio = Base64.getDecoder().decode(base64 == null ? "" : base64.trim());
        } catch (IllegalArgumentException e) {
            throw ApiException.badRequest("The recording is not base64");
        }
        if (audio.length < 4 || audio.length > MAX_BYTES || audio[0] != 'O' || audio[1] != 'g' || audio[2] != 'g'
                || audio[3] != 'S') {
            throw ApiException.badRequest("The recording must be an Ogg audio file of at most " + MAX_BYTES + " bytes");
        }
        String name = MONTH.format(call.getCreatedAt()) + "/" + call.getId() + ".ogg";
        Path target = dir.resolve(name);
        try {
            Files.createDirectories(target.getParent());
            Path part = target.resolveSibling(call.getId() + ".part");
            Files.write(part, audio);
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not save the recording of call " + call.getId(), e);
        }
        call.setRecordingRef(FILE + name);
        return true;
    }

    // ------------------------------------------------------------------ playing

    /** The admin, or staff the admin allowed to listen. Read from the database each time, so taking it away is immediate. */
    public boolean canHear(CurrentUser me) {
        return me.isAdmin() || users.findById(me.id()).map(u -> u.isActive() && u.isCanHearRecordings()).orElse(false);
    }

    /** True when the call's recording is a file held here (an outside provider's link is not). */
    public static boolean held(String ref) {
        return ref != null && ref.startsWith(FILE);
    }

    /** Refuses anyone who may not listen. */
    void mayHear(CurrentUser me) {
        if (!canHear(me)) {
            throw ApiException.forbidden("Only the admin, and staff the admin has allowed, can listen to call recordings");
        }
    }

    /** The recording of a call the person may see (VoiceQueryService checks that), recorded in the audit log. */
    ResponseEntity<Resource> play(CurrentUser me, VoiceCall call) {
        mayHear(me);
        Path file = file(call.getRecordingRef());
        if (file == null || !Files.isRegularFile(file)) {
            throw ApiException.notFound("Recording");
        }
        audit.record(me.id(), "RECORDING_PLAYED", "VOICE_CALL", null, call.getId().toString());
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("audio/ogg"))
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .body(new FileSystemResource(file));
    }

    // ------------------------------------------------------------------ removing

    /** Deletes these recordings' files once the current transaction has committed (at once when there is none). */
    public void deleteAfterCommit(Collection<String> refs) {
        List<Path> files = refs.stream().map(this::file).filter(f -> f != null).toList();
        if (files.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    delete(files);
                }
            });
        } else {
            delete(files);
        }
    }

    private static void delete(List<Path> files) {
        for (Path f : files) {
            try {
                Files.deleteIfExists(f);
            } catch (IOException e) {
                log.warn("Could not delete the recording {}: {}", f.getFileName(), e.getMessage());
            }
        }
    }

    /** The file a "file:" reference names, only if it is one of ours inside the folder; else null. */
    Path file(String ref) {
        if (!held(ref)) {
            return null;
        }
        String name = ref.substring(FILE.length());
        if (!OWN_PATH.matcher(name).matches()) {
            return null;
        }
        Path f = dir.resolve(name).normalize();
        return f.startsWith(dir) ? f : null;
    }

    @RestController
    static class RecordingController {

        private final VoiceQueryService queries;

        RecordingController(VoiceQueryService queries) {
            this.queries = queries;
        }

        @GetMapping("/api/voice/calls/{id}/recording")
        public ResponseEntity<Resource> play(@PathVariable UUID id) {
            return queries.recording(id);
        }
    }
}
