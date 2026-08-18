package main

import (
	"encoding/base64"
	"net/http"
	"testing"
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
