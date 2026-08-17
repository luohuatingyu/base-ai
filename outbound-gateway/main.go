package main

import (
	"context"
	"crypto/subtle"
	"fmt"
	"log"
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"os"
	"strings"
	"time"
)

// gateway 只允许经过审批的 HTTPS/HTTP 域名，并拒绝解析到内网地址的目标。
type gateway struct {
	token   string
	domains []string
	client  *http.Client
}

func main() {
	token := required("OUTBOUND_GATEWAY_TOKEN")
	if len(token) < 24 {
		log.Fatal("OUTBOUND_GATEWAY_TOKEN must contain at least 24 characters")
	}
	g := &gateway{token: token, domains: parseDomains(os.Getenv("OUTBOUND_ALLOWED_DOMAINS")), client: &http.Client{
		Timeout:       120 * time.Second,
		CheckRedirect: func(_ *http.Request, _ []*http.Request) error { return http.ErrUseLastResponse },
	}}
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
	if !g.authorized(r) {
		writeJSON(w, http.StatusUnauthorized, `{"error":"UNAUTHORIZED"}`)
		return
	}
	if r.Method == http.MethodConnect {
		g.connect(w, r)
		return
	}
	g.forward(w, r)
}

func (g *gateway) forward(w http.ResponseWriter, r *http.Request) {
	r.Body = http.MaxBytesReader(w, r.Body, 10*1024*1024)
	target := r.URL
	if !target.IsAbs() || target.Hostname() == "" {
		writeJSON(w, http.StatusBadRequest, `{"error":"ABSOLUTE_URL_REQUIRED"}`)
		return
	}
	if !g.allowed(target.Hostname()) {
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
	proxy.Transport = g.transport()
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

func (g *gateway) connect(w http.ResponseWriter, r *http.Request) {
	host, port, err := net.SplitHostPort(r.Host)
	if err != nil {
		host, port = r.Host, "443"
	}
	if port != "443" || !g.allowed(host) || !g.safeAddresses(r.Context(), host) {
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

func (g *gateway) transport() http.RoundTripper {
	return &http.Transport{Proxy: nil, DialContext: func(ctx context.Context, network, address string) (net.Conn, error) {
		host, port, err := net.SplitHostPort(address)
		if err != nil {
			return nil, err
		}
		if !g.allowed(host) || isPrivate(net.ParseIP(host)) {
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
	provided := r.Header.Get("X-Internal-Token")
	return len(provided) == len(g.token) && subtle.ConstantTimeCompare([]byte(provided), []byte(g.token)) == 1
}

func (g *gateway) allowed(host string) bool {
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	for _, domain := range g.domains {
		if host == domain || strings.HasSuffix(host, "."+domain) {
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
