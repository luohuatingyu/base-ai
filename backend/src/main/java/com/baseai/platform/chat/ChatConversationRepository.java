package com.baseai.platform.chat;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

/** 所有会话查询都限制所属用户，写操作使用数据库行锁。 */
public interface ChatConversationRepository extends JpaRepository<ChatConversation, Long> {
    Page<ChatConversation> findByOwnerIdOrderByUpdatedAtDescIdDesc(Long ownerId, Pageable pageable);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select conversation from ChatConversation conversation where conversation.id = :id and conversation.ownerId = :owner")
    Optional<ChatConversation> lockOwned(@Param("id") Long id, @Param("owner") Long owner);
}
