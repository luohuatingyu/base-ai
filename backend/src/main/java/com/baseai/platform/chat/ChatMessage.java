package com.baseai.platform.chat;

import jakarta.persistence.*;
import java.time.Instant;

/** 持久化用户消息和助手生成结果，包括失败时的部分文本。 */
@Entity
@Table(name = "ai_chat_message", uniqueConstraints = @UniqueConstraint(columnNames = {"conversation_id", "request_id", "role"}))
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long conversationId;
    @Column(nullable = false, length = 64) public String requestId;
    @Column(nullable = false, length = 16) public String role;
    @Column(nullable = false, columnDefinition = "LONGTEXT") public String contentJson;
    @Column(nullable = false, length = 16) public String status;
    @Column(columnDefinition = "TEXT") public String metadataJson;
    @Column(length = 64) public String traceId;
    @Column(nullable = false) public Instant createdAt = Instant.now();
}
