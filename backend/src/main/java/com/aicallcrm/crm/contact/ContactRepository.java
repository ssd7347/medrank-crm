package com.aicallcrm.crm.contact;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

/** Used by the voice agent and the campaign engine as well as the contact screens. */
public interface ContactRepository extends JpaRepository<Contact, Long>, JpaSpecificationExecutor<Contact> {

    @Override
    @EntityGraph(attributePaths = {"assignedTo", "branch"})
    Page<Contact> findAll(Specification<Contact> spec, Pageable pageable);

    Optional<Contact> findByPhone(String phone);

    @Query("select c from Contact c where c.phone = :phone or c.altPhone = :phone")
    List<Contact> findByAnyPhone(String phone);

    List<Contact> findByPhoneIn(Collection<String> phones);

    @Query("select c.status, count(c) from Contact c group by c.status")
    List<Object[]> countByStatus();

    long countByCreatedAtGreaterThanEqual(Instant since);
}
