package com.vectorflow.repository;

import com.vectorflow.model.Document;
import com.vectorflow.model.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface DocumentRepository extends JpaRepository<Document, UUID> {

    List<Document> findByUser(User user);

    List<Document> findByStatus(String status);

    @EntityGraph(attributePaths = "user")
    Page<Document> findAllBy(Pageable pageable);

    @EntityGraph(attributePaths = "user")
    Page<Document> findByUser(User user, Pageable pageable);
}
