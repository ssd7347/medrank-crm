package com.mbbscrm.crm.predictor;

import java.util.Collection;
import java.util.List;

import org.springframework.stereotype.Repository;

import com.mbbscrm.crm.college.College;
import com.mbbscrm.crm.college.CollegeFee;
import com.mbbscrm.crm.common.Category;
import com.mbbscrm.crm.common.Course;
import com.mbbscrm.crm.common.Quota;

import jakarta.persistence.EntityManager;

/** Read-side queries for the predictor. One indexed query per prediction (ix_cutoff_lookup). */
@Repository
public class PredictorQueries {

    private final EntityManager em;

    public PredictorQueries(EntityManager em) {
        this.em = em;
    }

    Integer latestYear(Course course) {
        return em.createQuery("select max(c.academicYear) from CutoffRecord c where c.course = :course", Integer.class)
                .setParameter("course", course)
                .getSingleResult();
    }

    List<CutoffRow> cutoffs(Course course, Category category, boolean pwd, Collection<Quota> quotas, int fromYear) {
        if (quotas.isEmpty()) {
            return List.of();
        }
        return em.createQuery("""
                        select new com.mbbscrm.crm.predictor.CutoffRow(
                            col, c.quota, c.academicYear, c.counsellingRound, c.closingRank)
                        from CutoffRecord c, College col
                        where col.id = c.collegeId and c.course = :course and c.category = :category
                          and c.pwd = :pwd and c.quota in :quotas and c.academicYear >= :fromYear
                        """, CutoffRow.class)
                .setParameter("course", course)
                .setParameter("category", category)
                .setParameter("pwd", pwd)
                .setParameter("quotas", quotas)
                .setParameter("fromYear", fromYear)
                .getResultList();
    }

    List<CollegeFee> fees(Course course, Collection<Long> collegeIds) {
        if (collegeIds.isEmpty()) {
            return List.of();
        }
        return em.createQuery("select f from CollegeFee f where f.course = :course and f.collegeId in :ids",
                        CollegeFee.class)
                .setParameter("course", course)
                .setParameter("ids", collegeIds)
                .getResultList();
    }
}
