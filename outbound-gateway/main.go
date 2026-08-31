package main

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/base64"
	"encoding/json"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"regexp"
	"strings"
	"time"
)

// gateway 只允许经过审批的 HTTPS/HTTP 域名，并拒绝解析到内网地址的目标。
type gateway struct {
	token      string
	sandboxKey string
	domains    []string
	now        func() time.Time
}

// sandboxClaims 把一次沙箱出站权限绑定到来源、包指纹、操作、精确域名和短时有效期。
type sandboxClaims struct {
	Source      string   `json:"s"`
	Fingerprint string   `json:"f"`
	Operation   string   `json:"o"`
	Domains     []string `json:"d"`
	IssuedAt    int64    `json:"iat"`
	ExpiresAt   int64    `json:"exp"`
	Nonce       string   `json:"n"`
}

// outboundPolicy 标记请求使用全局可信凭据还是插件沙箱精确域名凭据。
type outboundPolicy struct {
	sandbox bool
	domains []string
}

var sandboxFingerprint = regexp.MustCompile(`^[a-f0-9]{64}$`)
var sandboxNonce = regexp.MustCompile(`^[a-f0-9]{32}$`)

func main() {
	token := required("OUTBOUND_GATEWAY_TOKEN")
	if len(token) < 24 {
		log.Fatal("OUTBOUND_GATEWAY_TOKEN must contain at least 24 characters")
	}
	sandboxKey := required("PLUGIN_SANDBOX_EGRESS_SIGNING_KEY")
	if len(sandboxKey) < 32 {
		log.Fatal("PLUGIN_SANDBOX_EGRESS_SIGNING_KEY must contain at least 32 characters")
	}
	g := &gateway{token: token, sandboxKey: sandboxKey,
		domains: parseDomains(os.Getenv("OUTBOUND_ALLOWED_DOMAINS")), now: time.Now}
	server := &http.Server{Addr: ":8080", Handler: g, ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout: 130 * time.Second, WriteTimeout: 130 * time.Second, IdleTimeout: 30 * time.Second}
	log.Printf("outbound gateway started allowed_domains=%d", len(g.domains))
	log.Fatal(server.ListenAndServe())
}

func (g *gateway) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method == http.MethodGet && r.URL.Path == "/health" {
		writeJSON(w, http.StatusOK, `{"status":"UP"}`)
		return
	}
	if strings.HasPrefix(r.URL.Path, "/internal/workers/") {
		g.worker(w, r)
		return
	}
	policy, authorized := g.authorization(r)
	if !authorized {
		writeJSON(w, http.StatusUnauthorized, `{"error":"UNAUTHORIZED"}`)
		return
	}
	if r.Method == http.MethodConnect {
		g.connect(w, r, policy)
		return
	}
	g.forward(w, r, policy)
}

// worker 把 Backend 的受鉴权请求转发到隔离网络中的固定插件 Worker。
func (g *gateway) worker(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet && r.Method != http.MethodPost {
		writeJSON(w, http.StatusMethodNotAllowed, `{"error":"METHOD_NOT_ALLOWED"}`)
		return
	}
	prefix := ""
	target := ""
	if strings.HasPrefix(r.URL.Path, "/internal/workers/dify/") {
		prefix, target = "/internal/workers/dify", "http://dify-plugin-worker:8101"
	}
	if strings.HasPrefix(r.URL.Path, "/internal/workers/n8n/") {
		prefix, target = "/internal/workers/n8n", "http://n8n-plugin-worker:8102"
	}
	if target == "" {
		writeJSON(w, http.StatusNotFound, `{"error":"WORKER_NOT_FOUND"}`)
		return
	}
	upstream, _ := url.Parse(target)
	proxy := httputil.NewSingleHostReverseProxy(upstream)
	proxy.Director = func(request *http.Request) {
		request.URL.Scheme = upstream.Scheme
		request.URL.Host = upstream.Host
		request.URL.Path = strings.TrimPrefix(request.URL.Path, prefix)
		request.Host = upstream.Host
	}
	proxy.ErrorHandler = func(writer http.ResponseWriter, _ *http.Request, _ error) {
		writeJSON(writer, http.StatusBadGateway, `{"error":"WORKER_UPSTREAM_FAILED"}`)
	}
	proxy.ServeHTTP(w, r)
}

func (g *gateway) forward(w http.ResponseWriter, r *http.Request, policy outboundPolicy) {
	r.Body = http.MaxBytesReader(w, r.Body, 10*1024*1024)
	target := r.URL
	if !target.IsAbs() || target.Hostname() == "" {
		writeJSON(w, http.StatusBadRequest, `{"error":"ABSOLUTE_URL_REQUIRED"}`)
		return
	}
	if !g.allowedFor(policy, target.Hostname()) {
		writeJSON(w, http.StatusForbidden, `{"error":"OUTBOUND_HOST_FORBIDDEN"}`)
		return
	}
	if target.Scheme != "http" && target.Scheme != "https" {
		writeJSON(w, http.StatusBadRequest, `{"error":"OUTBOUND_SCHEME_FORBIDDEN"}`)
		return
	}
	if !g.safePort(target) {
		writeJSON(w, http.StatusForbidden, `{"error":"OUTBOUND_PORT_FORBIDDEN"}`)
		return
	}
	if !g.safeAddresses(r.Context(), target.Hostname()) {
		writeJSON(w, http.StatusForbidden, `{"error":"OUTBOUND_ADDRESS_FORBIDDEN"}`)
		return
	}
	proxy := httputil.NewSingleHostReverseProxy(&url.URL{Scheme: target.Scheme, Host: target.Host})
	proxy.Transport = g.transport(policy)
	proxy.Director = func(req *http.Request) {
		req.URL.Scheme = target.Scheme
		req.URL.Host = target.Host
		req.Host = target.Host
		req.Header.Del("X-Internal-Token")
		req.Header.Del("Proxy-Authorization")
		req.Header.Del("Proxy-Connection")
	}
	proxy.ErrorHandler = func(writer http.ResponseWriter, _ *http.Request, _ error) {
		writeJSON(writer, http.StatusBadGateway, `{"error":"OUTBOUND_UPSTREAM_FAILED"}`)
	}
	proxy.ServeHTTP(w, r)
}

func (g *gateway) connect(w http.ResponseWriter, r *http.Request, policy outboundPolicy) {
	host, port, err := net.SplitHostPort(r.Host)
	if err != nil {
		host, port = r.Host, "443"
	}
	if port != "443" || !g.allowedFor(policy, host) || !g.safeAddresses(r.Context(), host) {
		writeJSON(w, http.StatusForbidden, `{"error":"OUTBOUND_CONNECT_FORBIDDEN"}`)
		return
	}
	addresses, err := net.LookupHost(host)
	if err != nil || len(addresses) == 0 {
		writeJSON(w, http.StatusBadGateway, `{"error":"OUTBOUND_DNS_FAILED"}`)
		return
	}
	var upstream net.Conn
	for _, address := range addresses {
		if isPrivate(net.ParseIP(address)) {
			continue
		}
		upstream, err = net.DialTimeout("tcp", net.JoinHostPort(address, port), 10*time.Second)
		if err == nil {
			break
		}
	}
	if upstream == nil {
		writeJSON(w, http.StatusForbidden, `{"error":"OUTBOUND_ADDRESS_FORBIDDEN"}`)
		return
	}
	hijacker, ok := w.(http.Hijacker)
	if !ok {
		upstream.Close()
		writeJSON(w, http.StatusInternalServerError, `{"error":"CONNECT_UNSUPPORTED"}`)
		return
	}
	client, _, err := hijacker.Hijack()
	if err != nil {
		upstream.Close()
		return
	}
	_, _ = client.Write([]byte("HTTP/1.1 200 Connection Established\r\n\r\n"))
	go tunnel(upstream, client)
	go tunnel(client, upstream)
}

func (g *gateway) transport(policy outboundPolicy) http.RoundTripper {
	return &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		if !g.allowedFor(policy, host) || isPrivate(net.ParseIP(host)) {
			return nil, fmt.Errorf("outbound address forbidden")
		}
		ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
		if err != nil {
			return nil, err
		}
		for _, ip := range ips {
			if isPrivate(ip) {
				continue
			}
			conn, dialErr := (&net.Dialer{Timeout: 10 * time.Second}).DialContext(ctx, network, net.JoinHostPort(ip.String(), port))
			if dialErr == nil {
				return conn, nil
			}
		}
		return nil, fmt.Errorf("outbound address forbidden")
	}, TLSHandshakeTimeout: 10 * time.Second, ResponseHeaderTimeout: 120 * time.Second, IdleConnTimeout: 30 * time.Second}
}

func (g *gateway) authorized(r *http.Request) bool {
	_, authorized := g.authorization(r)
	return authorized
}

// authorization 校验全局凭据或短时沙箱令牌，并返回后续 Host 判定所需的权限范围。
func (g *gateway) authorization(r *http.Request) (outboundPolicy, bool) {
	provided := r.Header.Get("X-Internal-Token")
	if len(provided) == len(g.token) && subtle.ConstantTimeCompare([]byte(provided), []byte(g.token)) == 1 {
		return outboundPolicy{}, true
	}
	proxy := strings.TrimSpace(r.Header.Get("Proxy-Authorization"))
	if !strings.HasPrefix(proxy, "Basic ") {
		return outboundPolicy{}, false
	}
	decoded, err := base64.StdEncoding.DecodeString(strings.TrimSpace(strings.TrimPrefix(proxy, "Basic ")))
	if err != nil {
		return outboundPolicy{}, false
	}
	parts := strings.SplitN(string(decoded), ":", 2)
	if len(parts) != 2 {
		return outboundPolicy{}, false
	}
	if parts[0] == "sandbox" && parts[1] != "" {
		claims, valid := g.decodeSandboxToken(parts[1])
		if !valid {
			return outboundPolicy{}, false
		}
		return outboundPolicy{sandbox: true, domains: claims.Domains}, true
	}
	provided = parts[0]
	if parts[1] != "" {
		provided = parts[1]
	}
	valid := len(provided) == len(g.token) && subtle.ConstantTimeCompare([]byte(provided), []byte(g.token)) == 1
	return outboundPolicy{}, valid
}

// decodeSandboxToken 校验签名、结构、时间窗和全部最小权限声明。
func (g *gateway) decodeSandboxToken(token string) (sandboxClaims, bool) {
	parts := strings.Split(token, ".")
	if len(parts) != 2 || len(g.sandboxKey) < 32 {
		return sandboxClaims{}, false
	}
	payload, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil || len(payload) > 4096 {
		return sandboxClaims{}, false
	}
	signature, err := base64.RawURLEncoding.DecodeString(parts[1])
	if err != nil {
		return sandboxClaims{}, false
	}
	mac := hmac.New(sha256.New, []byte(g.sandboxKey))
	_, _ = mac.Write([]byte(parts[0]))
	if !hmac.Equal(signature, mac.Sum(nil)) {
		return sandboxClaims{}, false
	}
	var claims sandboxClaims
	if json.Unmarshal(payload, &claims) != nil || !sandboxFingerprint.MatchString(claims.Fingerprint) ||
		!sandboxNonce.MatchString(claims.Nonce) || !ListContains([]string{"DIFY", "N8N"}, claims.Source) ||
		!ListContains([]string{"inspect", "invoke"}, claims.Operation) || len(claims.Domains) > 64 {
		return sandboxClaims{}, false
	}
	now := time.Now()
	if g.now != nil {
		now = g.now()
	}
	if claims.IssuedAt > now.Unix()+30 || claims.ExpiresAt < now.Unix() || claims.ExpiresAt <= claims.IssuedAt ||
		claims.ExpiresAt-claims.IssuedAt > 11*60 {
		return sandboxClaims{}, false
	}
	seen := map[string]bool{}
	for _, domain := range claims.Domains {
		if normalizeExactDomain(domain) == "" || seen[domain] {
			return sandboxClaims{}, false
		}
		seen[domain] = true
	}
	return claims, true
}

// encodeSandboxToken 生成与 Broker 兼容的规范短时令牌，生产网关仅负责验证。
func encodeSandboxToken(key string, claims sandboxClaims) string {
	payload, _ := json.Marshal(claims)
	encoded := base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, []byte(key))
	_, _ = mac.Write([]byte(encoded))
	return encoded + "." + base64.RawURLEncoding.EncodeToString(mac.Sum(nil))
}

// allowedFor 对沙箱执行精确 Host 匹配，并始终与运维全局白名单取交集。
func (g *gateway) allowedFor(policy outboundPolicy, host string) bool {
	host = normalizeExactDomain(host)
	if host == "" || !g.allowed(host) {
		return false
	}
	if !policy.sandbox {
		return true
	}
	for _, domain := range policy.domains {
		if host == normalizeExactDomain(domain) {
			return true
		}
	}
	return false
}

func (g *gateway) allowed(host string) bool {
	host = normalizeExactDomain(host)
	for _, domain := range g.domains {
		if host == domain || strings.HasSuffix(host, "."+domain) {
			return true
		}
	}
	return false
}

// normalizeExactDomain 只接受 DNS 名称，禁止 IP、通配符和歧义尾点。
func normalizeExactDomain(value string) string {
	domain := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(value), "."))
	if len(domain) < 1 || len(domain) > 253 || net.ParseIP(domain) != nil || strings.Contains(domain, "*") {
		return ""
	}
	for _, label := range strings.Split(domain, ".") {
		if len(label) < 1 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return ""
		}
		for _, character := range label {
			if character != '-' && (character < 'a' || character > 'z') && (character < '0' || character > '9') {
				return ""
			}
		}
	}
	return domain
}

// ListContains 判断受控短列表是否包含目标值。
func ListContains(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}

func (g *gateway) safePort(target *url.URL) bool {
	port := target.Port()
	return port == "" || port == "80" && target.Scheme == "http" || port == "443" && target.Scheme == "https"
}

func (g *gateway) safeAddresses(ctx context.Context, host string) bool {
	if ip := net.ParseIP(host); ip != nil {
		return !isPrivate(ip)
	}
	ips, err := net.DefaultResolver.LookupIP(ctx, "ip", host)
	if err != nil || len(ips) == 0 {
		return false
	}
	for _, ip := range ips {
		if isPrivate(ip) {
			return false
		}
	}
	return true
}

func parseDomains(value string) []string {
	result := []string{}
	for _, raw := range strings.FieldsFunc(value, func(r rune) bool { return r == ',' || r == ' ' || r == '\n' || r == '\t' }) {
		domain := strings.ToLower(strings.TrimSuffix(strings.TrimSpace(raw), "."))
		if domain != "" {
			result = append(result, domain)
		}
	}
	return result
}

func isPrivate(ip net.IP) bool {
	return ip == nil || ip.IsLoopback() || ip.IsPrivate() || ip.IsLinkLocalUnicast() || ip.IsLinkLocalMulticast() || ip.IsUnspecified()
}

func tunnel(left, right net.Conn) {
	defer left.Close()
	defer right.Close()
	_, _ = ioCopy(right, left)
}

func ioCopy(dst, src net.Conn) (int64, error) { return copyBuffer(dst, src, make([]byte, 32*1024)) }

func copyBuffer(dst, src net.Conn, buffer []byte) (int64, error) {
	total := int64(0)
	for {
		n, err := src.Read(buffer)
		if n > 0 {
			written, writeErr := dst.Write(buffer[:n])
			total += int64(written)
			if writeErr != nil || written != n {
				return total, writeErr
			}
		}
		if err != nil {
			return total, err
		}
	}
}

func writeJSON(w http.ResponseWriter, status int, body string) {
	w.Header().Set("Content-Type", "application/json")
	w.Header().Set("X-Content-Type-Options", "nosniff")
	w.WriteHeader(status)
	_, _ = w.Write([]byte(body))
}

func required(name string) string {
	value := strings.TrimSpace(os.Getenv(name))
	if value == "" {
		log.Fatal(name + " is required")
	}
	return value
}
