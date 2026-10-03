package com.aicallcrm.crm.settings;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.aicallcrm.crm.audit.AuditService;
import com.aicallcrm.crm.common.ApiException;
import com.aicallcrm.crm.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * The white-label settings: company name, logo, the AI agent's name, the brand colour, the login-page wording
 * and what contacts are called ("Customer", "Patient", "Lead"...). Everyone can read them, even before signing
 * in, so the login page shows the customer's brand; only the admin can change them. The modules this
 * installation has bought ({@link Features}) are reported here too, so the screens can hide the others.
 */
@Service
public class SettingsService {

    public static final int MAX_LOGO_BYTES = 300 * 1024;

    public record Branding(String companyName, String agentName, String brandColor, String supportPhone,
                           String contactLabel, String loginHeadline, String loginText, String logoUrl,
                           String industryPack, List<String> features) {
    }

    public record BrandingRequest(@NotBlank @Size(max = 120) String companyName,
                                  @NotBlank @Size(max = 40) @Pattern(regexp = "^[\\p{L} .'-]+$",
                                          message = "letters only") String agentName,
                                  @NotBlank @Pattern(regexp = "^#[0-9a-fA-F]{6}$", message = "use a colour like #0f766e")
                                  String brandColor,
                                  @Pattern(regexp = "^([0-9+ ()-]{8,20})?$", message = "invalid phone") String supportPhone,
                                  @NotBlank @Size(max = 30) String contactLabel,
                                  @Size(max = 120) String loginHeadline,
                                  @Size(max = 300) String loginText) {
    }

    private final AppSettingRepository settings;
    private final AppLogoRepository logos;
    private final Features features;
    private final AuditService audit;

    SettingsService(AppSettingRepository settings, AppLogoRepository logos, Features features, AuditService audit) {
        this.settings = settings;
        this.logos = logos;
        this.features = features;
        this.audit = audit;
    }

    @Transactional(readOnly = true)
    public Branding branding() {
        return view(load());
    }

    @Transactional
    public Branding update(BrandingRequest req) {
        CurrentUser me = admin();
        AppSetting s = load();
        s.setCompanyName(req.companyName().trim());
        s.setAgentName(req.agentName().trim());
        s.setBrandColor(req.brandColor().toLowerCase());
        s.setSupportPhone(blankToNull(req.supportPhone()));
        s.setContactLabel(req.contactLabel().trim());
        s.setLoginHeadline(blankToNull(req.loginHeadline()));
        s.setLoginText(blankToNull(req.loginText()));
        s.touched(me.id());
        audit.record(me.id(), "SETTINGS_UPDATED", "SETTINGS", null, null);
        return view(s);
    }

    /** Called when a starter pack is loaded: the pack's word for contacts, and which pack it was. */
    @Transactional
    public void packApplied(String packId, String contactLabel) {
        AppSetting s = load();
        if (contactLabel != null && !contactLabel.isBlank()) {
            s.setContactLabel(contactLabel);
        }
        s.setIndustryPack(packId);
        s.touched(CurrentUser.get().id());
    }

    @Transactional
    public Branding uploadLogo(MultipartFile file) throws IOException {
        CurrentUser me = admin();
        if (file == null || file.isEmpty()) {
            throw ApiException.badRequest("Choose an image file");
        }
        if (file.getSize() > MAX_LOGO_BYTES) {
            throw ApiException.badRequest("The logo must be 300 KB or smaller");
        }
        byte[] data = file.getBytes();
        // Trust the bytes, not the file name or the browser's guess. SVG is refused: it can carry scripts.
        String type = imageType(data);
        if (type == null) {
            throw ApiException.badRequest("Use a PNG, JPEG or WebP image");
        }
        logos.findById(AppLogo.ID).ifPresentOrElse(l -> l.replace(type, data),
                () -> logos.save(new AppLogo(type, data)));
        AppSetting s = load();
        s.logoChanged();
        s.touched(me.id());
        audit.record(me.id(), "LOGO_UPDATED", "SETTINGS", null, null);
        return view(s);
    }

    @Transactional
    public Branding removeLogo() {
        CurrentUser me = admin();
        logos.deleteById(AppLogo.ID);
        AppSetting s = load();
        s.logoChanged();
        s.touched(me.id());
        audit.record(me.id(), "LOGO_REMOVED", "SETTINGS", null, null);
        return view(s);
    }

    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> logo() {
        return logos.findById(AppLogo.ID)
                .map(l -> ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(l.getContentType()))
                        .cacheControl(CacheControl.maxAge(Duration.ofDays(30)).cachePublic())
                        .header("X-Content-Type-Options", "nosniff")
                        .body(l.getData()))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    static String imageType(byte[] b) {
        if (b.length > 8 && (b[0] & 0xff) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') {
            return "image/png";
        }
        if (b.length > 3 && (b[0] & 0xff) == 0xff && (b[1] & 0xff) == 0xd8 && (b[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (b.length > 12 && b[0] == 'R' && b[1] == 'I' && b[2] == 'F' && b[3] == 'F'
                && b[8] == 'W' && b[9] == 'E' && b[10] == 'B' && b[11] == 'P') {
            return "image/webp";
        }
        return null;
    }

    private Branding view(AppSetting s) {
        // The version in the address makes browsers fetch a new logo as soon as it changes.
        String logoUrl = logos.existsById(AppLogo.ID) ? "/api/public/logo?v=" + s.getLogoVersion() : null;
        return new Branding(s.getCompanyName(), s.getAgentName(), s.getBrandColor(), s.getSupportPhone(),
                s.getContactLabel(), s.getLoginHeadline(), s.getLoginText(), logoUrl, s.getIndustryPack(),
                features.enabled());
    }

    private AppSetting load() {
        return settings.findById(AppSetting.ID).orElseThrow();
    }

    private static CurrentUser admin() {
        CurrentUser me = CurrentUser.get();
        if (!me.isAdmin()) {
            throw new ApiException(HttpStatus.FORBIDDEN, "Only the admin can change the company settings");
        }
        return me;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    @RestController
    @RequestMapping("/api")
    static class SettingsController {

        private final SettingsService service;

        SettingsController(SettingsService service) {
            this.service = service;
        }

        /** Open to everyone: the login page needs the company name, logo and colour. */
        @GetMapping("/public/branding")
        public Branding branding() {
            return service.branding();
        }

        @GetMapping("/public/logo")
        public ResponseEntity<byte[]> logo() {
            return service.logo();
        }

        @PutMapping("/settings/branding")
        public Branding update(@Valid @RequestBody BrandingRequest req) {
            return service.update(req);
        }

        @PostMapping("/settings/logo")
        public Branding uploadLogo(@RequestParam("file") MultipartFile file) throws IOException {
            return service.uploadLogo(file);
        }

        @DeleteMapping("/settings/logo")
        public Branding removeLogo() {
            return service.removeLogo();
        }
    }
}
