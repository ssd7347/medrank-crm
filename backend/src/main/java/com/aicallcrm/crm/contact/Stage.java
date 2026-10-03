package com.aicallcrm.crm.contact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** One step a contact can be at ("New", "Visited", "Booked"...). Named and ordered by each customer. */
@Entity
@Table(name = "contact_stage")
public class Stage {

    /** How a stage counts in reports. */
    public enum Category { OPEN, WON, LOST }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String stageKey;
    private String label;
    private String color;
    @Enumerated(EnumType.STRING)
    private Category category;
    private int sortOrder;
    private boolean builtIn;
    private Instant createdAt = Instant.now();

    protected Stage() {
    }

    Stage(String stageKey, String label, String color, Category category, int sortOrder) {
        this.stageKey = stageKey;
        this.label = label;
        this.color = color;
        this.category = category;
        this.sortOrder = sortOrder;
    }

    public Long getId() { return id; }
    public String getStageKey() { return stageKey; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getColor() { return color; }
    public void setColor(String color) { this.color = color; }
    public Category getCategory() { return category; }
    public void setCategory(Category category) { this.category = category; }
    public int getSortOrder() { return sortOrder; }
    public void setSortOrder(int sortOrder) { this.sortOrder = sortOrder; }
    public boolean isBuiltIn() { return builtIn; }
}

interface StageRepository extends JpaRepository<Stage, Long> {

    List<Stage> findAllByOrderBySortOrderAscIdAsc();

    Optional<Stage> findByStageKey(String stageKey);

    boolean existsByStageKey(String stageKey);
}
