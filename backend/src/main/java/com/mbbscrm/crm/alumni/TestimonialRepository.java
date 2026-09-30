package com.mbbscrm.crm.alumni;

import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TestimonialRepository extends JpaRepository<Testimonial, Long> {

    @EntityGraph(attributePaths = {"recordedBy", "reviewedBy"})
    List<Testimonial> findByAlumniIdOrderByCreatedAtDesc(Long alumniId);

    long countByStatus(Testimonial.Status status);
}
