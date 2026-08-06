package com.baseai.platform.security;

import com.baseai.platform.common.BusinessException;
import com.baseai.platform.config.PlatformProperties;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;

/** 管理浏览器会话 Cookie 与签名双提交 CSRF Token。 */
@Service
public class SessionCookieService {
    static final String CSRF_HEADER = "X-CSRF-Token";
    private static final String SESSION_PATH = "/api";
    private static final String CSRF_PATH = "/";
    private final TokenService tokenService;
    private final String sessionCookie;
    private final String csrfCookie;
    private final boolean secure;

    public SessionCookieService(TokenService tokenService, PlatformProperties properties) {
        this.tokenService = tokenService;
        String platformCode = properties.getPlatform().getCode().replaceAll("[^A-Za-z0-9_-]", "_");
        this.sessionCookie = "BAI_" + platformCode + "_SESSION";
        this.csrfCookie = "BAI_" + platformCode + "_CSRF";
        this.secure = properties.getSessionCookie().isSecure();
    }

    /** 写入不可被脚本读取的会话 Cookie 和可供双提交的 CSRF Cookie。 */
    public void write(HttpServletResponse response, String token, Instant expiresAt) {
        Duration maxAge = Duration.between(Instant.now(), expiresAt);
        if (maxAge.isNegative() || maxAge.isZero()) throw BusinessException.unauthorized("auth.tokenExpired");
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(sessionCookie, token, maxAge, true, SESSION_PATH).toString());
        response.addHeader(HttpHeaders.SET_COOKIE,
            cookie(csrfCookie, tokenService.createCsrfToken(token), maxAge, false, CSRF_PATH).toString());
    }

    /** 清除浏览器中的会话和 CSRF Cookie。 */
    public void clear(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(sessionCookie, "", Duration.ZERO, true, SESSION_PATH).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, cookie(csrfCookie, "", Duration.ZERO, false, CSRF_PATH).toString());
    }

    /** 返回请求携带的 HttpOnly 会话令牌，未携带时返回空。 */
    public String sessionToken(HttpServletRequest request) {
        return cookieValue(request, sessionCookie);
    }

    /** 优先返回显式 Bearer Token，否则返回浏览器会话 Cookie。 */
    public String authenticationToken(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && !authorization.isBlank()) {
            if (!authorization.startsWith("Bearer ")) throw BusinessException.unauthorized("auth.required");
            String token = authorization.substring(7).trim();
            if (token.isEmpty()) throw BusinessException.unauthorized("auth.required");
            return token;
        }
        String token = sessionToken(request);
        if (token == null || token.isBlank()) throw BusinessException.unauthorized("auth.required");
        return token;
    }

    /** 校验 Cookie、请求头与当前 JWT 绑定的签名 CSRF Token。 */
    public void validateCsrf(HttpServletRequest request, String token) {
        String cookieToken = cookieValue(request, csrfCookie);
        String headerToken = request.getHeader(CSRF_HEADER);
        if (cookieToken == null || headerToken == null || !cookieToken.equals(headerToken)
            || !tokenService.matchesCsrfToken(token, headerToken)) {
            throw BusinessException.forbidden("auth.csrfInvalid");
        }
    }

    /** 创建具有统一安全属性的 Host-only Cookie。 */
    private ResponseCookie cookie(String name, String value, Duration maxAge, boolean httpOnly, String path) {
        return ResponseCookie.from(name, value).httpOnly(httpOnly).secure(secure).sameSite("Strict")
            .path(path).maxAge(maxAge).build();
    }

    /** 按名称读取首个非空 Cookie。 */
    private String cookieValue(HttpServletRequest request, String name) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) return null;
        for (Cookie cookie : cookies) {
            if (name.equals(cookie.getName()) && cookie.getValue() != null && !cookie.getValue().isBlank()) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
