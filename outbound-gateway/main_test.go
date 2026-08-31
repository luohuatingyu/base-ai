package main

import (
	"encoding/base64"
	"net/http"
	"strings"
	"testing"
	"time"
)

func TestGatewayRejectsPrivateAndUnapprovedTargets(t *testing.T) {
	gateway := &gateway{token: "gateway-token-12345678901234567890", domains: []string{"api.example.com"}}
	if gateway.allowed("evil.example.com") {
		t.Fatal("unapproved host was allowed")
	}
	if gateway.safeAddresses(t.Context(), "127.0.0.1") {
		t.Fatal("loopback address was allowed")
	}
}

func TestGatewayAcceptsProxyCredential(t *testing.T) {
	gateway := &gateway{token: "gateway-token-12345678901234567890"}
	request, _ := http.NewRequest(http.MethodConnect, "http://gateway", nil)
	request.Header.Set("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte(gateway.token+":")))
	if !gateway.authorized(request) {
		t.Fatal("proxy credential was rejected")
	}
}

// TestGatewayScopesSandboxCredential 验证短时沙箱令牌只能访问当前插件获批的精确域名。
func TestGatewayScopesSandboxCredential(t *testing.T) {
	now := time.Unix(1_800_000_000, 0)
	gateway := &gateway{token: "gateway-token-12345678901234567890",
		sandboxKey: "sandbox-signing-key-12345678901234567890", domains: []string{"example.com"},
		now: func() time.Time { return now }}
	token := encodeSandboxToken(gateway.sandboxKey, sandboxClaims{Source: "DIFY", Fingerprint: strings.Repeat("a", 64),
		Operation: "invoke", Domains: []string{"api.example.com"}, IssuedAt: now.Unix(), ExpiresAt: now.Add(time.Minute).Unix(),
		Nonce: "0123456789abcdef0123456789abcdef"})
	request, _ := http.NewRequest(http.MethodConnect, "http://gateway", nil)
	request.Header.Set("Proxy-Authorization", "Basic "+base64.StdEncoding.EncodeToString([]byte("sandbox:"+token)))

	policy, allowed := gateway.authorization(request)
	if !allowed || !gateway.allowedFor(policy, "api.example.com") {
		t.Fatal("approved exact host was rejected")
	}
	if gateway.allowedFor(policy, "other.example.com") || gateway.allowedFor(policy, "sub.api.example.com") {
		t.Fatal("sandbox token escaped its exact approved host")
	}

	gateway.now = func() time.Time { return now.Add(2 * time.Minute) }
	if _, accepted := gateway.authorization(request); accepted {
		t.Fatal("expired sandbox credential was accepted")
	}
}
