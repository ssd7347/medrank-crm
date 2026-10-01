package com.aicallcrm.crm.settings;

import java.time.Instant;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The customer's own branding and wording (single row). Changing these needs no code change. */
@Entity
@Table(name = "app_setting")
public class AppSetting {

    static final int ID = 1;

    @Id
    private Integer id;

    private String companyName;
    private String agentName;
    private String brandColor;
    private String supportPhone;
    private String contactLabel;
    private Long updatedBy;
    private Instant updatedAt;

    protected AppSetting() {
    }

    public String getCompanyName() { return companyName; }
    public void setCompanyName(String companyName) { this.companyName = companyName; }
    public String getAgentName() { return agentName; }
    public void setAgentName(String agentName) { this.agentName = agentName; }
    public String getBrandColor() { return brandColor; }
    public void setBrandColor(String brandColor) { this.brandColor = brandColor; }
    public String getSupportPhone() { return supportPhone; }
    public void setSupportPhone(String supportPhone) { this.supportPhone = supportPhone; }
    public String getContactLabel() { return contactLabel; }
    public void setContactLabel(String contactLabel) { this.contactLabel = contactLabel; }
    public void touched(Long userId) {
        this.updatedBy = userId;
        this.updatedAt = Instant.now();
    }
}

interface AppSettingRepository extends JpaRepository<AppSetting, Integer> {
}
