package com.aicallcrm.crm.settings;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.assistant.AssistantService;
import com.aicallcrm.crm.assistant.AssistantService.FaqRequest;
import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.FieldDefinition.AgentAccess;
import com.aicallcrm.crm.contact.FieldDefinition.Type;
import com.aicallcrm.crm.contact.FieldService;
import com.aicallcrm.crm.contact.FieldService.FieldRequest;
import com.aicallcrm.crm.contact.Stage.Category;
import com.aicallcrm.crm.contact.StageService;
import com.aicallcrm.crm.security.CurrentUser;
import com.aicallcrm.crm.voice.Voice.ScriptKind;
import com.aicallcrm.crm.voice.VoiceScriptService;
import com.aicallcrm.crm.voice.VoiceScriptService.ScriptRequest;

import tools.jackson.databind.ObjectMapper;

/**
 * Industry starter packs: a ready set of stages, custom fields, call scripts and knowledge-base answers for one
 * kind of business, kept as JSON under {@code resources/packs}. Loading one only ever adds: anything with the
 * same name is left as the customer has it. Scripts arrive as drafts the admin must approve, and answers arrive
 * switched off, because both still contain [placeholders] only the customer can fill in.
 */
@Service
public class StarterPacks {

    /** Shown in this order. Each id is a file {@code packs/<id>.json}. */
    static final List<String> IDS = List.of("clinic", "finance", "real_estate", "education", "insurance");
    static final String MODEL = "claude-haiku-4-5-20251001";

    record PackStage(String label, String color, Category category) {
    }

    record PackField(String label, Type type, AgentAccess agentAccess, boolean verifyWith) {
    }

    record PackScript(String name, ScriptKind kind, String purpose, String opening) {
    }

    record PackFaq(String question, String answer, String keywords) {
    }

    record Pack(String id, String name, String description, String contactLabel, List<PackStage> stages,
                List<PackField> fields, List<PackScript> scripts, List<PackFaq> faqs) {
    }

    public record PackSummary(String id, String name, String description, String contactLabel, List<String> stages,
                              List<String> fields, List<String> scripts, int faqs) {
    }

    public record Applied(String id, int stagesAdded, int fieldsAdded, int scriptsAdded, int faqsAdded) {
    }

    private final Map<String, Pack> packs = new LinkedHashMap<>();
    private final String scriptTemplate;
    private final StageService stages;
    private final FieldService fields;
    private final VoiceScriptService scripts;
    private final AssistantService faqs;
    private final SettingsService settings;
    private final AuditService audit;

    StarterPacks(ObjectMapper mapper, StageService stages, FieldService fields, VoiceScriptService scripts,
                 AssistantService faqs, SettingsService settings, AuditService audit) {
        this.stages = stages;
        this.fields = fields;
        this.scripts = scripts;
        this.faqs = faqs;
        this.settings = settings;
        this.audit = audit;
        for (String id : IDS) {
            try (InputStream in = new ClassPathResource("packs/" + id + ".json").getInputStream()) {
                packs.put(id, mapper.readValue(in, Pack.class));
            } catch (IOException e) {
                throw new UncheckedIOException("Starter pack " + id, e);
            }
        }
        try (InputStream in = new ClassPathResource("packs/script-template.txt").getInputStream()) {
            scriptTemplate = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public List<PackSummary> all() {
        return packs.values().stream().map(p -> new PackSummary(p.id(), p.name(), p.description(), p.contactLabel(),
                p.stages().stream().map(PackStage::label).toList(), p.fields().stream().map(PackField::label).toList(),
                p.scripts().stream().map(PackScript::name).toList(), p.faqs().size())).toList();
    }

    @Transactional
    public Applied apply(String id) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only the admin can load a starter pack");
        }
        Pack p = packs.get(id);
        if (p == null) {
            throw ApiException.notFound("Starter pack");
        }

        Set<String> stageNames = stages.all().stream().map(s -> s.label().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        int stagesAdded = 0;
        for (PackStage s : p.stages()) {
            if (stageNames.add(s.label().toLowerCase(Locale.ROOT))) {
                stages.createEntity(s.label(), s.color(), s.category());
                stagesAdded++;
            }
        }

        Set<String> fieldKeys = fields.all().stream().map(FieldService.FieldView::key)
                .collect(Collectors.toCollection(HashSet::new));
        int fieldsAdded = 0;
        int order = 10 * (fieldKeys.size() + 1);
        for (PackField f : p.fields()) {
            if (fieldKeys.add(FieldService.keyOf(f.label()))) {
                fields.create(new FieldRequest(f.label(), f.type(), f.agentAccess(), f.verifyWith(), order, true));
                order += 10;
                fieldsAdded++;
            }
        }

        Set<String> scriptNames = scripts.all().stream().map(VoiceScriptService.ScriptView::name)
                .map(n -> n.toLowerCase(Locale.ROOT)).collect(Collectors.toCollection(HashSet::new));
        int scriptsAdded = 0;
        for (PackScript s : p.scripts()) {
            if (scriptNames.add(s.name().toLowerCase(Locale.ROOT))) {
                scripts.create(new ScriptRequest(s.name(), s.kind(), MODEL,
                        scriptTemplate.replace("{{purpose}}", s.purpose()).trim(), s.opening()));
                scriptsAdded++;
            }
        }

        Set<String> questions = faqs.all().stream().map(f -> f.question().toLowerCase(Locale.ROOT))
                .collect(Collectors.toCollection(HashSet::new));
        int faqsAdded = 0;
        int faqOrder = 100;
        for (PackFaq f : p.faqs()) {
            if (questions.add(f.question().toLowerCase(Locale.ROOT))) {
                faqs.save(null, new FaqRequest(f.question(), f.answer(), f.keywords(), faqOrder, false));
                faqOrder += 10;
                faqsAdded++;
            }
        }

        settings.packApplied(p.id(), p.contactLabel());
        audit.record(me.id(), "STARTER_PACK_APPLIED", "SETTINGS", null, p.id());
        return new Applied(p.id(), stagesAdded, fieldsAdded, scriptsAdded, faqsAdded);
    }

    @RestController
    @RequestMapping("/api/settings/packs")
    static class StarterPackController {

        private final StarterPacks service;

        StarterPackController(StarterPacks service) {
            this.service = service;
        }

        @GetMapping
        public List<PackSummary> all() {
            return service.all();
        }

        @PostMapping("/{id}/apply")
        public Applied apply(@PathVariable String id) {
            return service.apply(id);
        }
    }
}
