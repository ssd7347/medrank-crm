package com.mbbscrm.crm.assistant;

import java.time.Duration;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.mbbscrm.crm.assistant.AssistantService.Answer;
import com.mbbscrm.crm.assistant.AssistantService.CallbackRequest;
import com.mbbscrm.crm.assistant.AssistantService.FaqRequest;
import com.mbbscrm.crm.assistant.AssistantService.FaqView;
import com.mbbscrm.crm.assistant.AssistantService.PublicFaq;
import com.mbbscrm.crm.common.ApiException;
import com.mbbscrm.crm.common.Language;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * {@code /api/faq} is the staff knowledge-base editor. {@code /api/public/assistant} is open to website
 * visitors without a login, so it is rate limited and returns only published FAQ text.
 */
@RestController
@RequestMapping("/api")
public class AssistantController {

    private final AssistantService service;
    private final RateLimiter limiter;

    public AssistantController(AssistantService service, RateLimiter limiter) {
        this.service = service;
        this.limiter = limiter;
    }

    public record AskRequest(@NotNull Language language, @NotBlank @Size(max = 300) String question) {
    }

    // ---- staff

    @GetMapping("/faq")
    public List<FaqView> all() {
        return service.all();
    }

    @PostMapping("/faq")
    @ResponseStatus(HttpStatus.CREATED)
    public FaqView create(@Valid @RequestBody FaqRequest req) {
        return service.save(null, req);
    }

    @PutMapping("/faq/{id}")
    public FaqView update(@PathVariable Long id, @Valid @RequestBody FaqRequest req) {
        return service.save(id, req);
    }

    // ---- public

    @GetMapping("/public/assistant/faq")
    public List<PublicFaq> top(@RequestParam(defaultValue = "ENGLISH") Language language, HttpServletRequest http) {
        limit("faq:" + client(http), 60, Duration.ofMinutes(1));
        return service.topQuestions(language);
    }

    @PostMapping("/public/assistant/ask")
    public Answer ask(@Valid @RequestBody AskRequest req, HttpServletRequest http) {
        limit("ask:" + client(http), 30, Duration.ofMinutes(1));
        return service.answer(req.language(), req.question());
    }

    @PostMapping("/public/assistant/callback")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void callback(@Valid @RequestBody CallbackRequest req, HttpServletRequest http) {
        limit("callback:" + client(http), 5, Duration.ofHours(1));
        // A cap across all visitors too, so a flood from many addresses cannot fill the lead list.
        limit("callback:all", 300, Duration.ofHours(24));
        if (req.website() != null && !req.website().isBlank()) {
            return; // a bot filled the hidden field; pretend it worked
        }
        service.requestCallback(req);
    }

    private void limit(String key, int max, Duration period) {
        if (!limiter.allow(key, max, period)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "Too many requests. Please try again in a little while.");
        }
    }

    /** The visitor's address as reported by our own front-end proxy, else the direct connection. */
    private static String client(HttpServletRequest http) {
        String forwarded = http.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String first = forwarded.split(",")[0].trim();
            if (first.length() <= 45) {
                return first;
            }
        }
        return http.getRemoteAddr();
    }
}
