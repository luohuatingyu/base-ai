package com.baseai.platform.chat;

import jakarta.persistence.*;
import java.time.Instant;

/** 登录用户私有会话，租约限制同一会话同时生成。 */
@Entity
@Table(name = "ai_chat_conversation")
public class ChatConversation {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) public Long id;
    @Column(nullable = false) public Long ownerId;
    @Column(nullable = false, length = 120) public String title = "";
    @Column(nullable = false, columnDefinition = "LONGTEXT") public String settingsJson = "{}";
    public Long activeMessageId;
    public Instant leaseUntil;
    @Column(nullable = false) public Instant createdAt = Instant.now();
    @Column(nullable = false) public Instant updatedAt = Instant.now();
}
