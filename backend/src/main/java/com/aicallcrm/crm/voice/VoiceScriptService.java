package com.aicallcrm.crm.voice;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.user.AppUserRepository;
import com.aicallcrm.crm.voice.Voice.ScriptKind;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Versioned call scripts with an approval step. Saving always creates a new version of the named script; only
 * a version the admin has approved is used on a real call, and approving one retires the previous version.
 * Exactly one INBOUND script can be in use, for people who ring the company.
 */
@Service
public class VoiceScriptService {

    public record ScriptRequest(@NotBlank @Size(max = 80) String name, @NotNull ScriptKind kind,
                                @NotBlank @Size(max = 60) String model,
                                @NotBlank @Size(max = 20000) String systemPrompt,
                                @NotBlank @Size(max = 1000) String openingLine) {
    }

    public record ScriptView(Long id, String name, ScriptKind kind, int version, String model, String systemPrompt,
                             String openingLine, boolean active, boolean approved, Instant approvedAt,
                             String approvedBy, Instant createdAt) {
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
        admin();
        return scripts.findAllByOrderByNameAscVersionDesc().stream().map(this::view).toList();
    }

    /** Names of the outbound scripts that can be chosen for a campaign. */
    @Transactional(readOnly = true)
    public List<String> outboundNames() {
        return scripts.findAllByOrderByNameAscVersionDesc().stream().filter(s -> s.getKind() == ScriptKind.OUTBOUND)
                .map(VoiceScript::getName).distinct().toList();
    }

    @Transactional
    public ScriptView create(ScriptRequest req) {
        CurrentUser me = admin();
        String name = req.name().trim();
        List<VoiceScript> versions = scripts.findByNameOrderByVersionDesc(name);
        if (!versions.isEmpty() && versions.get(0).getKind() != req.kind()) {
            throw ApiException.badRequest("\"" + name + "\" is already used for " + versions.get(0).getKind().name()
                    .toLowerCase() + " calls; choose another name");
        }
        int next = versions.stream().mapToInt(VoiceScript::getVersion).max().orElse(0) + 1;
        VoiceScript s = scripts.save(new VoiceScript(name, req.kind(), next, req.model().trim(),
                req.systemPrompt().trim(), req.openingLine().trim(), me.id()));
        audit.record(me.id(), "VOICE_SCRIPT_CREATED", "VOICE_SCRIPT", s.getId(), name + " v" + next);
        return view(s);
    }

    @Transactional
    public ScriptView approve(Long id) {
        CurrentUser me = admin();
        VoiceScript s = scripts.findById(id).orElseThrow(() -> ApiException.notFound("Script"));
        scripts.findByNameOrderByVersionDesc(s.getName()).forEach(VoiceScript::retire);
        if (s.getKind() == ScriptKind.INBOUND) {
            // Only one way of answering the phone at a time.
            scripts.findAll().stream().filter(o -> o.getKind() == ScriptKind.INBOUND).forEach(VoiceScript::retire);
        }
        s.approve(me.id());
        audit.record(me.id(), "VOICE_SCRIPT_APPROVED", "VOICE_SCRIPT", s.getId(), s.getName() + " v" + s.getVersion());
        return view(s);
    }

    /** The approved version of a named script, for a real call. */
    @Transactional(readOnly = true)
    public Optional<VoiceScript> approved(String name) {
        return scripts.findFirstByNameAndActiveTrueOrderByVersionDesc(name).filter(VoiceScript::usable);
    }

    @Transactional(readOnly = true)
    public Optional<VoiceScript> approvedInbound() {
        return scripts.findFirstByKindAndActiveTrueOrderByIdAsc(ScriptKind.INBOUND).filter(VoiceScript::usable);
    }

    /** For the test console only: the approved version, or failing that the newest draft. */
    @Transactional(readOnly = true)
    public Optional<VoiceScript> newest(String name) {
        return approved(name).or(() -> scripts.findByNameOrderByVersionDesc(name).stream().findFirst());
    }

    /** Fills {{placeholders}}; an unknown placeholder becomes empty rather than being read out. */
    static String render(String text, Map<String, String> variables) {
        String out = text;
        for (Map.Entry<String, String> e : variables.entrySet()) {
            out = out.replace("{{" + e.getKey() + "}}", e.getValue() == null ? "" : e.getValue());
        }
        return out.replaceAll("\\{\\{[a-z_]+}}", "").replaceAll(" {2,}", " ").replaceAll(" ([?.,!])", "$1").trim();
    }

    private ScriptView view(VoiceScript s) {
        String approver = s.getApprovedBy() == null ? null
                : users.findById(s.getApprovedBy()).map(u -> u.getFullName()).orElse(null);
        return new ScriptView(s.getId(), s.getName(), s.getKind(), s.getVersion(), s.getModel(), s.getSystemPrompt(),
                s.getOpeningLine(), s.isActive(), s.getApprovedAt() != null, s.getApprovedAt(), approver,
                s.getCreatedAt());
    }

    private static CurrentUser admin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only the admin can change call scripts");
        }
        return me;
    }
}
