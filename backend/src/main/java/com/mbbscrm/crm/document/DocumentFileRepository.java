package com.mbbscrm.crm.document;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentFileRepository extends JpaRepository<DocumentFile, Long> {

    @EntityGraph(attributePaths = "uploadedBy")
    List<DocumentFile> findByStudentDocumentIdInOrderByUploadedAtDesc(Collection<Long> studentDocumentIds);
}
