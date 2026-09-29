package com.mbbscrm.crm.document;

import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.DomicileStatus;
import com.mbbscrm.crm.common.Nationality;
import com.mbbscrm.crm.student.Student;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A checklist item such as "Community certificate", with the rule for which students need it. */
@Entity
@Table(name = "document_type")
public class DocumentType {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String code;
    private String name;
    @Enumerated(EnumType.STRING)
    private AppliesWhen appliesWhen;
    private boolean requiresExpiry;
    private int sortOrder;
    private boolean active = true;

    /** True if this student must provide the document; OPTIONAL items are never required. */
    public boolean requiredFor(Student s) {
        return switch (appliesWhen) {
            case ALWAYS -> true;
            case RESERVED_CATEGORY -> s.getCategory() != null && s.getCategory() != Category.GEN;
            case EWS -> s.getCategory() == Category.EWS;
            case DOMICILED -> s.getDomicileStatus() == DomicileStatus.DOMICILED;
            case PWD -> s.isPwd();
            case NRI -> s.getNationality() == Nationality.NRI || s.getNationality() == Nationality.OCI
                    || s.isNriSponsored();
            case OPTIONAL -> false;
        };
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public AppliesWhen getAppliesWhen() { return appliesWhen; }
    public void setAppliesWhen(AppliesWhen appliesWhen) { this.appliesWhen = appliesWhen; }
    public boolean isRequiresExpiry() { return requiresExpiry; }
    public void setRequiresExpiry(boolean requiresExpiry) { this.requiresExpiry = requiresExpiry; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
