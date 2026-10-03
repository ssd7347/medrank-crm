package com.aicallcrm.crm.contact;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

// Repositories of the contact module, kept together because each is only a few lines.

interface ContactActivityRepository extends JpaRepository<ContactActivity, Long> {

    @EntityGraph(attributePaths = "createdBy")
    List<ContactActivity> findByContactIdOrderByCreatedAtDesc(Long contactId);
}

interface FollowUpRepository extends JpaRepository<FollowUp, Long> {

    @EntityGraph(attributePaths = "assignedTo")
    List<FollowUp> findByContactIdOrderByDueAtAsc(Long contactId);

    @EntityGraph(attributePaths = {"contact", "assignedTo"})
    @Query("""
            select f from FollowUp f
            where f.assignedTo.id = :userId and f.completedAt is null and f.dueAt <= :before
            order by f.dueAt
            """)
    List<FollowUp> findOpenForUserDueBefore(Long userId, Instant before);

    /** Open follow-ups due by {@code before} whose assignee has not been reminded yet. */
    @EntityGraph(attributePaths = {"contact", "assignedTo"})
    @Query("""
            select f from FollowUp f
            where f.completedAt is null and f.remindedAt is null and f.dueAt <= :before
            order by f.dueAt
            """)
    List<FollowUp> findDueForReminder(Instant before);
}

interface ImportBatchRepository extends JpaRepository<ImportBatch, Long> {

    List<ImportBatch> findTop50ByOrderByCreatedAtDesc();
}

interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, Long> {

    List<FieldDefinition> findAllByOrderBySortOrderAscIdAsc();

    Optional<FieldDefinition> findByFieldKey(String fieldKey);
}
