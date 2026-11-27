package com.vectorflow.repository;

import com.vectorflow.model.Conversation;
import com.vectorflow.model.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface ConversationRepository extends JpaRepository<Conversation, UUID> {

    List<Conversation> findByUserOrderByUpdatedAtDesc(User user, Pageable pageable);

    Optional<Conversation> findByIdAndUser(UUID id, User user);
}
