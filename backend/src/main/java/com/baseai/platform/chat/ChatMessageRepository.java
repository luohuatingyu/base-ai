package com.baseai.platform.chat;

import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** 按服务器生成的消息编号读取对话，不接受客户端伪造历史。 */
public interface ChatMessageRepository extends JpaRepository<ChatMessage, Long> {
    List<ChatMessage> findByConversationIdOrderByIdAsc(Long conversationId);
    boolean existsByConversationIdAndRequestId(Long conversationId, String requestId);
    void deleteByConversationId(Long conversationId);
}
