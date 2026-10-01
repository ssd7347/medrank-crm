package com.aicallcrm.crm.settings;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The white-label settings: company name, the AI agent's name, the brand colour and what contacts are called
 * ("Customer", "Patient", "Lead"...). Everyone can read them, even before signing in, so the login page shows
 * the customer's brand; only the admin can change them.
 */
@Service
public class SettingsService {

    public record Branding(String companyName, String agentName, String brandColor, String supportPhone,
                           String contactLabel) {
        static Branding of(AppSetting s) {
            return new Branding(s.getCompanyName(), s.getAgentName(), s.getBrandColor(), s.getSupportPhone(),
                    s.getContactLabel());
        }
    }

    public record BrandingRequest(@NotBlank @Size(max = 120) String companyName,
                                  @NotBlank @Size(max = 40) @Pattern(regexp = "^[\\p{L} .'-]+$",
                                          message = "letters only") String agentName,
                                  @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "use a colour like #0f766e")
                                  String brandColor,
                                  @Pattern(regexp = "^([0-9+ ()-]{8,20})?$", message = "invalid phone") String supportPhone,
                                  @NotBlank @Size(max = 30) String contactLabel) {
    }

    private final AppSettingRepository settings;
    private final AuditService audit;

    SettingsService(AppSettingRepository settings, AuditService audit) {
        this.settings = settings;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Branding branding() {
        return Branding.of(load());
    }

    @Transactional
    public Branding update(BrandingRequest req) {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the admin can change the company settings");
        }
        AppSetting s = load();
        s.setCompanyName(req.companyName().trim());
        s.setAgentName(req.agentName().trim());
        s.setBrandColor(req.brandColor().toLowerCase());
        s.setSupportPhone(req.supportPhone() == null || req.supportPhone().isBlank() ? null : req.supportPhone().trim());
        s.setContactLabel(req.contactLabel().trim());
        s.touched(me.id());
        audit.record(me.id(), "SETTINGS_UPDATED", "SETTINGS", null, null);
        return Branding.of(s);
    }

    private AppSetting load() {
        return settings.findById(AppSetting.ID).orElseThrow();
    }

    @RestController
    @RequestMapping("/api")
    static class SettingsController {

        private final SettingsService service;

        SettingsController(SettingsService service) {
            this.service = service;
        }

        /** Open to everyone: the login page needs the company name and colour. */
        @GetMapping("/public/branding")
        public Branding branding() {
            return service.branding();
        }

        @PutMapping("/settings/branding")
        public Branding update(@Valid @RequestBody BrandingRequest req) {
            return service.update(req);
        }
    }
}
