package com.baseai.platform.chat;

import com.fasterxml.jackson.databind.JsonNode;

/** 聊天 Agent 可独立授权的工具元数据，不携带接口凭据或执行配置。 */
public record ChatAgentToolOption(String code, String name, String description, JsonNode inputSchema) {}
