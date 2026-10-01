package com.mbbscrm.crm.voice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.mbbscrm.crm.audit.AuditService;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Language;
import com.mbbscrm.crm.security.CurrentUser;
import com.mbbscrm.crm.user.AppUserRepository;
import com.mbbscrm.crm.voice.Voice.Purpose;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Versioned call scripts with an approval step (spec 18.11 and task E9-1). Saving always creates a new
 * version; only a version an admin has approved is ever used on a real call, and approving one retires the
 * previous version for that purpose and language.
 */
@Service
public class VoiceScriptService {

    public record ScriptRequest(@NotNull Purpose purpose, @NotNull Language language,
                                @NotBlank @Size(max = 60) String model,
                                @NotBlank @Size(max = 20000) String systemPrompt,
                                @NotBlank @Size(max = 1000) String openingLine) {
    }

    public record ScriptView(Long id, Purpose purpose, Language language, int version, String model,
                             String systemPrompt, String openingLine, boolean active, boolean approved,
                             Instant approvedAt, String approvedBy, Instant createdAt) {
    }

    private final VoiceScriptRepository scripts;
    private final AppUserRepository users;
    private final AuditService audit;

    public VoiceScriptService(VoiceScriptRepository scripts, AppUserRepository users, AuditService audit) {
        this.scripts = scripts;
        this.users = users;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<ScriptView> all() {
        return scripts.findAllByOrderByPurposeAscLanguageAscVersionDesc().stream().map(this::view).toList();
    }

    @Transactional
    public ScriptView create(ScriptRequest req) {
        CurrentUser me = admin();
        int next = scripts.findByPurposeAndLanguageOrderByVersionDesc(req.purpose(), req.language()).stream()
                .mapToInt(VoiceScript::getVersion).max().orElse(0) + 1;
        VoiceScript s = scripts.save(new VoiceScript(req.purpose(), req.language(), next, req.model().trim(),
                req.systemPrompt().trim(), req.openingLine().trim(), me.id()));
        audit.record(me.id(), "VOICE_SCRIPT_CREATED", "VOICE_SCRIPT", s.getId(),
                s.getPurpose() + " " + s.getLanguage() + " v" + s.getVersion());
        return view(s);
    }

    @Transactional
    public ScriptView approve(Long id) {
        CurrentUser me = admin();
        VoiceScript s = scripts.findById(id).orElseThrow(() -> ApiException.notFound("Script"));
        scripts.findByPurposeAndLanguageOrderByVersionDesc(s.getPurpose(), s.getLanguage()).forEach(VoiceScript::retire);
        s.approve(me.id());
        audit.record(me.id(), "VOICE_SCRIPT_APPROVED", "VOICE_SCRIPT", s.getId(),
                s.getPurpose() + " " + s.getLanguage() + " v" + s.getVersion());
        return view(s);
    }

    /** The approved script for a real call: the caller's language if there is one, otherwise English. */
    @Transactional(readOnly = true)
    public Optional<VoiceScript> approved(Purpose purpose, Language language) {
        return scripts.findFirstByPurposeAndLanguageAndActiveTrueOrderByVersionDesc(purpose, language)
                .or(() -> scripts.findFirstByPurposeAndLanguageAndActiveTrueOrderByVersionDesc(purpose,
                        Language.ENGLISH))
                .filter(VoiceScript::usable);
    }

    /** For the test console only: the approved script, or failing that the newest draft. */
    @Transactional(readOnly = true)
    public Optional<VoiceScript> newest(Purpose purpose, Language language) {
        return approved(purpose, language)
                .or(() -> scripts.findByPurposeAndLanguageOrderByVersionDesc(purpose, language).stream().findFirst())
                .or(() -> scripts.findByPurposeAndLanguageOrderByVersionDesc(purpose, Language.ENGLISH).stream()
                        .findFirst());
    }

    /** Fills {{placeholders}}; an unknown placeholder becomes empty rather than being read out. */
    static String render(String text, Map<String, String> variables) {
        String out = text;
        for (Map.Entry<String, String> e : variables.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out.replaceAll("\\{\\{[a-z_]+}}", "").replaceAll(" {2,}", " ").trim();
    }

    private ScriptView view(VoiceScript s) {
        String approver = s.getApprovedBy() == null ? null
                : users.findById(s.getApprovedBy()).map(u -> u.getFullName()).orElse(null);
        return new ScriptView(s.getId(), s.getPurpose(), s.getLanguage(), s.getVersion(), s.getModel(),
                s.getSystemPrompt(), s.getOpeningLine(), s.isActive(), s.getApprovedAt() != null, s.getApprovedAt(),
                approver, s.getCreatedAt());
    }

    private static CurrentUser admin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only an admin can change call scripts");
        }
        return me;
    }
}
