package com.baseai.platform.modeltype;

/** 模型接入协议对应的业务操作，避免调用方直接判断具体模型类型。 */
public enum LlmOperation {
    CHAT,
    EMBEDDING,
    IMAGE_GENERATION,
    VIDEO_GENERATION,
    HEALTH_CHECK
}
