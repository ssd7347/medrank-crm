package com.mbbscrm.crm.counselling;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

@Entity
@Table(name = "choice_list_item")
public class ChoiceListItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "choice_list_id")
    private ChoiceList choiceList;

    private int position;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "college_id")
    private College college;

    @Enumerated(EnumType.STRING)
    private Course course;
    @Enumerated(EnumType.STRING)
    private Quota quota;
    private String note;

    protected ChoiceListItem() {
    }

    public ChoiceListItem(ChoiceList choiceList, int position, College college, Course course, Quota quota,
                          String note) {
        this.choiceList = choiceList;
        this.position = position;
        this.college = college;
        this.course = course;
        this.quota = quota;
        this.note = note;
    }

    public Long getId() { return id; }
    public int getPosition() { return position; }
    public College getCollege() { return college; }
    public Course getCourse() { return course; }
    public Quota getQuota() { return quota; }
    public String getNote() { return note; }
}
