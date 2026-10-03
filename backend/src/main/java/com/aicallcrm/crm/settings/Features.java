package com.aicallcrm.crm.settings;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import com.aicallcrm.crm.common.ApiException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * The modules this installation has bought, from {@code app.features} (environment variable FEATURES). The seller
 * sets it per customer; a switched-off module disappears from the menu and its API answers 403, so a plan cannot
 * be unlocked from the browser.
 */
@Component
public class Features implements WebMvcConfigurer {

    public enum Feature { AI_CALLS, IMPORT, EXPORT, FOLLOW_UPS }

    private final Set<Feature> on;

    public Features(@Value("${app.features:ALL}") String csv) {
        this.on = parse(csv);
    }

    static Set<Feature> parse(String csv) {
        if (csv == null || csv.isBlank() || csv.trim().equalsIgnoreCase("ALL")) {
            return EnumSet.allOf(Feature.class);
        }
        Set<Feature> set = EnumSet.noneOf(Feature.class);
        for (String part : csv.split(",")) {
            String name = part.trim().toUpperCase(Locale.ROOT);
            if (!name.isEmpty()) {
                // A typo should fail loudly at start-up, not silently switch a paid module off.
                set.add(Arrays.stream(Feature.values()).filter(f -> f.name().equals(name)).findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Unknown feature in FEATURES: " + name)));
            }
        }
        return set;
    }

    public boolean isOn(Feature f) {
        return on.contains(f);
    }

    public List<String> enabled() {
        return on.stream().map(Enum::name).toList();
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        gate(registry, Feature.AI_CALLS, "/api/voice/**", "/api/faq/**", "/api/faq", "/api/contacts/*/voice-calls",
                "/api/contacts/*/voice-consent");
        gate(registry, Feature.IMPORT, "/api/contacts/import/**", "/api/contacts/import", "/api/contacts/imports/**",
                "/api/contacts/imports");
        gate(registry, Feature.EXPORT, "/api/contacts/export");
        gate(registry, Feature.FOLLOW_UPS, "/api/follow-ups/**", "/api/contacts/*/follow-ups/**",
                "/api/contacts/*/follow-ups");
    }

    private void gate(InterceptorRegistry registry, Feature feature, String... paths) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
                if (!isOn(feature)) {
                    throw new ApiException(HttpStatus.FORBIDDEN, "This feature is not part of your plan");
                }
                return true;
            }
        }).addPathPatterns(paths);
    }
}
