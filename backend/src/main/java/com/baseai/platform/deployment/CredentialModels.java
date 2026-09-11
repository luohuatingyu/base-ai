package com.baseai.platform.deployment;

import java.time.LocalDateTime;

/** 服务器凭据接口模型。 */
public final class CredentialModels {
    private CredentialModels() { }
    public record Command(String label, String type, String username, String publicKey, String privateKey, String certificate, String password, Boolean enabled) { }
    public record View(Long id, String label, String type, String username, String publicKey, String certificate, boolean hasPrivateKey, boolean hasPassword, boolean enabled, LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
