package com.mbbscrm.crm.counselling;

import java.time.Instant;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

/** A body that runs counselling: MCC centrally, or a state's DME / selection committee (spec 4.15). */
@Entity
@Table(name = "counselling_authority")
public class CounsellingAuthority {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    @Enumerated(EnumType.STRING)
    private AuthorityType authorityType;
    /** Set for STATE authorities: only colleges in this state belong to its counselling. */
    private String state;
    private String website;
    private boolean active = true;
    private Instant createdAt;

    @PrePersist
    void onCreate() {
        createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AuthorityType getAuthorityType() { return authorityType; }
    public void setAuthorityType(AuthorityType authorityType) { this.authorityType = authorityType; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getWebsite() { return website; }
    public void setWebsite(String website) { this.website = website; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
