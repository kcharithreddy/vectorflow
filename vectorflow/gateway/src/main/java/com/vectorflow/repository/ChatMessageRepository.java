package com.vectorflow.repository;

import com.vectorflow.model.ChatMessage;
import com.vectorflow.model.Conversation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, UUID> {

    List<ChatMessage> findByConversationOrderByCreatedAtAsc(Conversation conversation);

    /** Newest first; reverse for chronological order. */
    List<ChatMessage> findByConversationOrderByCreatedAtDesc(Conversation conversation, Pageable pageable);
}
