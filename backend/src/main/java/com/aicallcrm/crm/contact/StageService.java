package com.aicallcrm.crm.contact;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.contact.Stage.Category;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The stages a contact moves through. Each customer names, colours and orders their own ("New → Visited →
 * Booked"). The built-in ones ({@link ContactStatus}) can be renamed but not deleted, because the AI agent
 * moves people into them; a custom stage can be deleted once its contacts have been moved elsewhere.
 */
@Service
public class StageService {

    public static final int MAX_STAGES = 30;

    public record StageRequest(@NotBlank @Size(max = 40) String label,
                               @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "use a colour like #0f766e")
                               String color,
                               @NotNull Category category) {
    }

    public record StageView(Long id, String key, String label, String color, Category category, int sortOrder,
                            boolean builtIn) {
        static StageView of(Stage s) {
            return new StageView(s.getId(), s.getStageKey(), s.getLabel(), s.getColor(), s.getCategory(),
                    s.getSortOrder(), s.isBuiltIn());
        }
    }

    public record OrderRequest(@NotEmpty List<Long> ids) {
    }

    private final StageRepository stages;
    private final ContactRepository contacts;
    private final AuditService audit;

    StageService(StageRepository stages, ContactRepository contacts, AuditService audit) {
        this.stages = stages;
        this.contacts = contacts;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public List<StageView> all() {
        return stages.findAllByOrderBySortOrderAscIdAsc().stream().map(StageView::of).toList();
    }

    @Transactional(readOnly = true)
    public List<String> keys() {
        return stages.findAllByOrderBySortOrderAscIdAsc().stream().map(Stage::getStageKey).toList();
    }

    /** The key, if it names a stage; otherwise a 400 the screens can show. */
    @Transactional(readOnly = true)
    public String require(String key) {
        String k = key == null ? "" : key.trim().toUpperCase(Locale.ROOT);
        if (!stages.existsByStageKey(k)) {
            throw ApiException.badRequest("There is no stage called \"" + key + "\"");
        }
        return k;
    }

    /** The name people see, for timeline entries such as "New → Visited". */
    @Transactional(readOnly = true)
    public String label(String key) {
        return stages.findByStageKey(key).map(Stage::getLabel).orElse(key);
    }

    @Transactional(readOnly = true)
    public Map<String, String> labels() {
        return stages.findAll().stream().collect(Collectors.toMap(Stage::getStageKey, Stage::getLabel));
    }

    @Transactional
    public StageView create(StageRequest req) {
        CurrentUser me = admin();
        Stage s = createEntity(req.label(), req.color(), req.category());
        audit.record(me.id(), "STAGE_CREATED", "STAGE", s.getId(), s.getStageKey());
        return StageView.of(s);
    }

    /** Used by the starter packs too: a stage with the same name is reused rather than duplicated. */
    @Transactional
    public Stage createEntity(String label, String color, Category category) {
        String name = label.trim();
        Optional<Stage> same = stages.findAll().stream().filter(s -> s.getLabel().equalsIgnoreCase(name)).findFirst();
        if (same.isPresent()) {
            return same.get();
        }
        if (stages.count() >= MAX_STAGES) {
            throw ApiException.badRequest("At most " + MAX_STAGES + " stages");
        }
        String base = keyOf(name);
        if (base.isEmpty()) {
            throw ApiException.badRequest("A stage needs a name with letters or digits in it");
        }
        String key = base;
        for (int n = 2; stages.existsByStageKey(key); n++) {
            String suffix = "_" + n;
            key = base.substring(0, Math.min(base.length(), 20 - suffix.length())) + suffix;
        }
        // New stages go just before the "lost" ones, which is where people expect them.
        List<Stage> all = stages.findAllByOrderBySortOrderAscIdAsc();
        int order = all.stream().filter(s -> s.getCategory() != Category.LOST).mapToInt(Stage::getSortOrder)
                .max().orElse(0) + 1;
        for (Stage s : all) {
            if (s.getSortOrder() >= order) {
                s.setSortOrder(s.getSortOrder() + 1);
            }
        }
        return stages.save(new Stage(key, name, color.toLowerCase(Locale.ROOT), category, order));
    }

    @Transactional
    public StageView update(Long id, StageRequest req) {
        CurrentUser me = admin();
        Stage s = stages.findById(id).orElseThrow(() -> ApiException.notFound("Stage"));
        String name = req.label().trim();
        if (stages.findAll().stream().anyMatch(o -> !o.getId().equals(id) && o.getLabel().equalsIgnoreCase(name))) {
            throw ApiException.conflict("Another stage is already called \"" + name + "\"");
        }
        s.setLabel(name);
        s.setColor(req.color().toLowerCase(Locale.ROOT));
        // What a built-in stage means to the AI agent does not change, so neither does how it counts.
        if (!s.isBuiltIn()) {
            s.setCategory(req.category());
        }
        audit.record(me.id(), "STAGE_UPDATED", "STAGE", s.getId(), s.getStageKey());
        return StageView.of(s);
    }

    @Transactional
    public List<StageView> reorder(OrderRequest req) {
        CurrentUser me = admin();
        Map<Long, Stage> byId = stages.findAll().stream().collect(Collectors.toMap(Stage::getId, Function.identity()));
        if (req.ids().size() != byId.size() || !byId.keySet().containsAll(req.ids())) {
            throw ApiException.badRequest("List every stage exactly once");
        }
        for (int i = 0; i < req.ids().size(); i++) {
            byId.get(req.ids().get(i)).setSortOrder((i + 1) * 10);
        }
        audit.record(me.id(), "STAGES_REORDERED", "STAGE", null, null);
        return all();
    }

    /** Deleting a stage that still has contacts needs {@code moveTo}: where those contacts go instead. */
    @Transactional
    public void delete(Long id, String moveTo) {
        CurrentUser me = admin();
        Stage s = stages.findById(id).orElseThrow(() -> ApiException.notFound("Stage"));
        if (s.isBuiltIn()) {
            throw ApiException.conflict("\"" + s.getLabel() + "\" is used by the AI agent; rename it instead");
        }
        long inUse = contacts.countInStage(s.getStageKey());
        if (inUse > 0) {
            if (moveTo == null || moveTo.isBlank()) {
                throw ApiException.conflict(inUse + " contact(s) are at \"" + s.getLabel()
                        + "\". Choose a stage to move them to first.").with("contacts", inUse);
            }
            String target = require(moveTo);
            if (target.equals(s.getStageKey())) {
                throw ApiException.badRequest("Choose a different stage to move them to");
            }
            contacts.moveStage(s.getStageKey(), target);
        }
        stages.delete(s);
        audit.record(me.id(), "STAGE_DELETED", "STAGE", id, s.getStageKey());
    }

    static String keyOf(String label) {
        String k = label.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
        return k.length() > 20 ? k.substring(0, 20).replaceAll("_+$", "") : k;
    }

    private static CurrentUser admin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw ApiException.forbidden("Only the admin can change the stages");
        }
        return me;
    }

    @RestController
    @RequestMapping("/api/stages")
    static class StageController {

        private final StageService service;

        StageController(StageService service) {
            this.service = service;
        }

        @GetMapping
        public List<StageView> all() {
            return service.all();
        }

        @PostMapping
        public StageView create(@Valid @RequestBody StageRequest req) {
            return service.create(req);
        }

        @PutMapping("/{id}")
        public StageView update(@PathVariable Long id, @Valid @RequestBody StageRequest req) {
            return service.update(id, req);
        }

        @PutMapping("/order")
        public List<StageView> reorder(@Valid @RequestBody OrderRequest req) {
            return service.reorder(req);
        }

        @DeleteMapping("/{id}")
        public void delete(@PathVariable Long id, @RequestParam(required = false) String moveTo) {
            service.delete(id, moveTo);
        }
    }
}
