package com.baseai.platform.security;

import java.time.Instant;

public record TokenClaims(Long userId, String username, String tokenId, Instant expiresAt) {}
