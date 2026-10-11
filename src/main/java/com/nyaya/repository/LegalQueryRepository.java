package com.nyaya.repository;

import com.nyaya.entity.LegalQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

// Repository for query logs and chat session history
@Repository
public interface LegalQueryRepository extends JpaRepository<LegalQuery, Long> {

    // Fetch conversation thread ordered chronologically
    List<LegalQuery> findByConversationIdOrderByCreatedAtAsc(String conversationId);

    // Fetch the 20 most recent queries for audit view
    List<LegalQuery> findTop20ByOrderByCreatedAtDesc();
}


