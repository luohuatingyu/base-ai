package com.baseai.platform.domain;

import java.time.Duration;

public enum ApiKeyRateLimitType {
    SECOND(Duration.ofSeconds(1)),
    MINUTE(Duration.ofMinutes(1)),
    HOUR(Duration.ofHours(1)),
    DAY(Duration.ofDays(1)),
    UNLIMITED(null);

    private final Duration windowDuration;

    ApiKeyRateLimitType(Duration windowDuration) {
        this.windowDuration = windowDuration;
    }

    /** 判断当前配置是否启用请求频次限制。 */
    public boolean isLimited() {
        return windowDuration != null;
    }

    /** 返回固定窗口持续时间。 */
    public Duration windowDuration() {
        return windowDuration;
    }
}
