package com.aicallcrm.crm.contact;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.common.ApiException;

/**
 * Tags: free-form labels on contacts ("Hot lead", "Chennai", "VIP"). A tag keeps the spelling it was first
 * typed with; later "hot lead" or "HOT LEAD " land on the same tag, so lists and campaigns never split.
 */
@Service
public class TagService {

    public static final int MAX_LENGTH = 40;
    public static final int MAX_PER_CONTACT = 20;

    public record TagCount(String tag, long contacts) {
    }

    private final ContactRepository contacts;

    TagService(ContactRepository contacts) {
        this.contacts = contacts;
    }

    @Transactional(readOnly = true)
    public List<TagCount> all() {
        return contacts.tagCounts().stream().map(r -> new TagCount((String) r[0], (Long) r[1])).toList();
    }

    /** Lower-case form -> the spelling already in use. Load once per request when tagging many contacts. */
    @Transactional(readOnly = true)
    public Map<String, String> spellings() {
        Map<String, String> m = new HashMap<>();
        for (Object[] r : contacts.tagCounts()) {
            m.putIfAbsent(((String) r[0]).toLowerCase(Locale.ROOT), (String) r[0]);
        }
        return m;
    }

    /** "  hot   lead " -> "hot lead"; null for nothing. Too long is an error rather than a silent cut. */
    public static String clean(String raw) {
        if (raw == null) {
            return null;
        }
        String t = raw.trim().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return null;
        }
        if (t.length() > MAX_LENGTH) {
            throw ApiException.badRequest("A tag can be at most " + MAX_LENGTH + " characters: \"" + t + "\"");
        }
        return t;
    }

    /** "VIP, Chennai; hot lead" -> three tags. Used for spreadsheet columns. */
    public static List<String> split(String cell) {
        if (cell == null) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (String part : cell.split("[,;|]")) {
            String t = clean(part);
            if (t != null) {
                out.add(t);
            }
        }
        return out;
    }

    /** Adds tags to a contact, reusing the CRM's existing spelling. {@code spellings} is updated with new ones. */
    public static void add(Contact c, Collection<String> tags, Map<String, String> spellings) {
        Set<String> have = lower(c.getTags());
        for (String raw : tags) {
            String t = clean(raw);
            if (t == null) {
                continue;
            }
            String key = t.toLowerCase(Locale.ROOT);
            if (have.add(key)) {
                if (c.getTags().size() >= MAX_PER_CONTACT) {
                    throw ApiException.badRequest("A contact can have at most " + MAX_PER_CONTACT + " tags");
                }
                c.getTags().add(spellings.computeIfAbsent(key, k -> t));
            }
        }
    }

    public static void remove(Contact c, Collection<String> tags) {
        Set<String> drop = lower(tags);
        c.getTags().removeIf(t -> drop.contains(t.toLowerCase(Locale.ROOT)));
    }

    /** Replaces a contact's tags with exactly these. */
    public static void set(Contact c, Collection<String> tags, Map<String, String> spellings) {
        Set<String> keep = new LinkedHashSet<>();
        for (String raw : tags) {
            String t = clean(raw);
            if (t != null) {
                keep.add(t.toLowerCase(Locale.ROOT));
            }
        }
        c.getTags().removeIf(t -> !keep.contains(t.toLowerCase(Locale.ROOT)));
        add(c, tags, spellings);
    }

    public static boolean has(Contact c, String tag) {
        return tag != null && c.getTags().stream().anyMatch(t -> t.equalsIgnoreCase(tag.trim()));
    }

    public static List<String> sorted(Collection<String> tags) {
        return tags.stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    private static Set<String> lower(Collection<String> tags) {
        Set<String> s = new LinkedHashSet<>();
        tags.forEach(t -> s.add(t.toLowerCase(Locale.ROOT).trim()));
        return s;
    }

    @RestController
    static class TagController {

        private final TagService service;

        TagController(TagService service) {
            this.service = service;
        }

        /** Every tag in use and how many contacts have it: for filters, suggestions and campaigns. */
        @GetMapping("/api/tags")
        public List<TagCount> all() {
            return service.all();
        }
    }
}
