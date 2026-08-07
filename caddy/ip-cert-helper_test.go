package main

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// 验证多个站点分组分别加载证书、规范化域名并生成仅当前用户可读的 PEM 包。
func TestPrepareHTTPSSites(t *testing.T) {
	directory := t.TempDir()
	tlsRoot := filepath.Join(directory, "tls")
	createTestDomainKeyPair(t, tlsRoot, "site-a", []string{"ai.example.com", "api.example.com"})
	createTestDomainKeyPair(t, tlsRoot, "site-b", []string{"console.example.net"})
	configPath := filepath.Join(directory, "https-sites.yml")
	contents := `sites:
  - domains: [&primary "AI.Example.COM", *primary, api.example.com]
    tls_cert_file: site-a/fullchain.pem
    tls_key_file: site-a/privkey.pem
  - domains:
      - console.example.net
    tls_cert_file: site-b/fullchain.pem
    tls_key_file: site-b/privkey.pem
`
	if err := os.WriteFile(configPath, []byte(contents), 0o600); err != nil {
		t.Fatal(err)
	}
	bundleDir := filepath.Join(directory, "bundles")
	hosts, err := prepareHTTPSSites(configPath, tlsRoot, bundleDir)
	if err != nil {
		t.Fatalf("prepare HTTPS sites: %v", err)
	}
	if got, want := strings.Join(hosts, " "), "ai.example.com api.example.com console.example.net"; got != want {
		t.Fatalf("hosts = %q, want %q", got, want)
	}
	entries, err := os.ReadDir(bundleDir)
	if err != nil {
		t.Fatal(err)
	}
	if got, want := len(entries), 2; got != want {
		t.Fatalf("bundle count = %d, want %d", got, want)
	}
	for _, entry := range entries {
		path := filepath.Join(bundleDir, entry.Name())
		bundle, readErr := os.ReadFile(path)
		if readErr != nil {
			t.Fatal(readErr)
		}
		if _, parseErr := tls.X509KeyPair(bundle, bundle); parseErr != nil {
			t.Fatalf("parse generated bundle %s: %v", entry.Name(), parseErr)
		}
		info, statErr := os.Stat(path)
		if statErr != nil || info.Mode().Perm() != 0o600 {
			t.Fatalf("bundle mode = %v, err=%v", info.Mode().Perm(), statErr)
		}
	}
}

// 参数化验证非法 schema、重复域名、路径越界、SAN 不匹配和密钥不匹配均被拒绝。
func TestPrepareHTTPSSitesRejectsInvalidConfiguration(t *testing.T) {
	directory := t.TempDir()
	tlsRoot := filepath.Join(directory, "tls")
	createTestDomainKeyPair(t, tlsRoot, "site-a", []string{"ai.example.com"})
	createTestDomainKeyPair(t, tlsRoot, "site-b", []string{"api.example.com"})
	outsideCertificate := filepath.Join(directory, "outside.pem")
	certificate, err := os.ReadFile(filepath.Join(tlsRoot, "site-a", "fullchain.pem"))
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(outsideCertificate, certificate, 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(outsideCertificate, filepath.Join(tlsRoot, "site-a", "escape.pem")); err != nil {
		t.Fatal(err)
	}
	tests := map[string]string{
		"empty sites":       "sites: []\n",
		"unknown field":     "sites: [{domains: [ai.example.com], tls_cert_file: site-a/fullchain.pem, tls_key_file: site-a/privkey.pem, redirect: true}]\n",
		"multiple docs":     "sites: []\n---\nsites: []\n",
		"non-string domain": "sites: [{domains: [42], tls_cert_file: site-a/fullchain.pem, tls_key_file: site-a/privkey.pem}]\n",
		"wildcard":          "sites: [{domains: ['*.example.com'], tls_cert_file: site-a/fullchain.pem, tls_key_file: site-a/privkey.pem}]\n",
		"path traversal":    "sites: [{domains: [ai.example.com], tls_cert_file: ../fullchain.pem, tls_key_file: site-a/privkey.pem}]\n",
		"symlink traversal": "sites: [{domains: [ai.example.com], tls_cert_file: site-a/escape.pem, tls_key_file: site-a/privkey.pem}]\n",
		"duplicate domain":  "sites: [{domains: [ai.example.com], tls_cert_file: site-a/fullchain.pem, tls_key_file: site-a/privkey.pem}, {domains: [AI.EXAMPLE.COM], tls_cert_file: site-b/fullchain.pem, tls_key_file: site-b/privkey.pem}]\n",
		"SAN mismatch":      "sites: [{domains: [other.example.com], tls_cert_file: site-a/fullchain.pem, tls_key_file: site-a/privkey.pem}]\n",
		"key mismatch":      "sites: [{domains: [ai.example.com], tls_cert_file: site-a/fullchain.pem, tls_key_file: site-b/privkey.pem}]\n",
	}
	for name, contents := range tests {
		t.Run(name, func(t *testing.T) {
			configPath := filepath.Join(t.TempDir(), "https-sites.yml")
			if err := os.WriteFile(configPath, []byte(contents), 0o600); err != nil {
				t.Fatal(err)
			}
			if _, err := prepareHTTPSSites(configPath, tlsRoot, filepath.Join(t.TempDir(), "bundles")); err == nil {
				t.Fatal("invalid HTTPS sites configuration unexpectedly succeeded")
			}
		})
	}
}

// 验证 HTTPS 站点配置的文件大小、分组数量和域名总数上限。
func TestPrepareHTTPSSitesRejectsResourceLimits(t *testing.T) {
	oversizedPath := filepath.Join(t.TempDir(), "oversized.yml")
	if err := os.WriteFile(oversizedPath, []byte("sites: []\n#"+strings.Repeat("x", maxHTTPSitesConfigBytes)), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := prepareHTTPSSites(oversizedPath, t.TempDir(), filepath.Join(t.TempDir(), "bundles")); err == nil {
		t.Fatal("oversized HTTPS sites configuration unexpectedly succeeded")
	}

	values := make([]string, maxConfiguredDomains+1)
	for index := range values {
		values[index] = fmt.Sprintf("host-%d.example.com", index)
	}
	if _, err := normalizeDomains(values); err == nil {
		t.Fatal("domain count above limit unexpectedly succeeded")
	}
}

// 验证 localhost 和双 IPv4 规范化、顺序保持和重复地址去重。
func TestParseCertificateNames(t *testing.T) {
	names, err := parseCertificateNames("localhost 203.0.113.10, 192.168.1.10 203.0.113.10 localhost")
	if err != nil {
		t.Fatalf("parse valid certificate names: %v", err)
	}
	if got, want := len(names.dnsNames), 1; got != want {
		t.Fatalf("DNS name count = %d, want %d", got, want)
	}
	if got, want := names.dnsNames[0], "localhost"; got != want {
		t.Fatalf("DNS name = %s, want %s", got, want)
	}
	if got, want := len(names.ipAddresses), 2; got != want {
		t.Fatalf("IP count = %d, want %d", got, want)
	}
	if got, want := names.ipAddresses[0].String(), "203.0.113.10"; got != want {
		t.Fatalf("first IP = %s, want %s", got, want)
	}
	if got, want := names.ipAddresses[1].String(), "192.168.1.10"; got != want {
		t.Fatalf("second IP = %s, want %s", got, want)
	}
}

// 验证空值、IPv6、越界值、非规范前导零和任意 DNS 名都被拒绝。
func TestParseCertificateNamesRejectsInvalidValues(t *testing.T) {
	for _, value := range []string{"", "2001:db8::1", "192.168.1.256", "192.168.001.10", "host.example"} {
		if _, err := parseCertificateNames(value); err == nil {
			t.Fatalf("parseCertificateNames(%q) unexpectedly succeeded", value)
		}
	}
}

// 验证预配置 IP 支持逗号和空白分隔、稳定去重，并复用动态学习的地址安全边界。
func TestParseConfiguredIPs(t *testing.T) {
	addresses, err := parseConfiguredIPs("192.168.1.20, 203.0.113.10\n192.168.1.20 127.0.0.1")
	if err != nil {
		t.Fatalf("parse configured IPs: %v", err)
	}
	if got, want := strings.Join(addresses, " "), "192.168.1.20 203.0.113.10"; got != want {
		t.Fatalf("configured IPs = %q, want %q", got, want)
	}
	for _, value := range []string{"2001:db8::1", "192.168.001.10", "0.0.0.0", "169.254.1.1", "224.0.0.1", "host.example"} {
		if _, parseErr := parseConfiguredIPs(value); parseErr == nil {
			t.Fatalf("parseConfiguredIPs(%q) unexpectedly succeeded", value)
		}
	}
}

// 验证预配置和动态地址共同受 IP 证书 SAN 总量保护，重复地址只计数一次。
func TestNamesForIPHostsEnforcesCombinedLimit(t *testing.T) {
	configured := make([]string, maxIPCertificateHosts)
	for index := range configured {
		configured[index] = fmt.Sprintf("10.0.%d.%d", index/254, index%254+1)
	}
	if _, err := namesForIPHosts(configured, []string{configured[0]}); err != nil {
		t.Fatalf("deduplicated address limit: %v", err)
	}
	if _, err := namesForIPHosts(configured, []string{"192.168.1.20"}); err == nil {
		t.Fatal("combined address limit unexpectedly accepted an extra learned IP")
	}
}

// 参数化验证回环、内网和公网 IPv4 可学习，其他 Host 类别被拒绝。
func TestCanonicalBootstrapIPv4(t *testing.T) {
	for _, value := range []string{"127.0.0.2", "10.20.30.40:81", "192.168.1.20", "8.8.8.8"} {
		if _, err := canonicalBootstrapIPv4(value); err != nil {
			t.Fatalf("canonicalBootstrapIPv4(%q): %v", value, err)
		}
	}
	for _, value := range []string{"localhost", "example.com", "192.168.001.2", "192.168.1.256", "0.0.0.0", "169.254.1.1", "224.0.0.1", "[::1]:81"} {
		if _, err := canonicalBootstrapIPv4(value); err == nil {
			t.Fatalf("canonicalBootstrapIPv4(%q) unexpectedly succeeded", value)
		}
	}
}

// 验证首次 HTTP 请求签发证书、持久化地址并返回保留路径查询的 HTTPS 跳转。
func TestBootstrapLearnsHostAndRedirects(t *testing.T) {
	service, manager := createTestBootstrapService(t)
	var reloads atomic.Int32
	service.reload = func(names certificateNames) error {
		reloads.Add(1)
		if !containsString(stringsForIPs(names.ipAddresses), "192.168.1.20") {
			t.Fatalf("reloaded SANs = %v", names.ipAddresses)
		}
		return nil
	}

	request := httptest.NewRequest(http.MethodGet, "http://192.168.1.20:81/api/open/health?ready=1", nil)
	request.Host = "192.168.1.20:81"
	response := httptest.NewRecorder()
	service.ServeHTTP(response, request)
	if response.Code != http.StatusPermanentRedirect {
		t.Fatalf("status = %d, body = %s", response.Code, response.Body.String())
	}
	if got, want := response.Header().Get("Location"), "https://192.168.1.20:444/api/open/health?ready=1"; got != want {
		t.Fatalf("Location = %q, want %q", got, want)
	}
	if got := readFileText(t, service.statePath); got != "192.168.1.20\n" {
		t.Fatalf("learned state = %q", got)
	}
	leaf := readLeaf(t, manager.outputCertPath)
	if !containsString(stringsForIPs(leaf.IPAddresses), "192.168.1.20") {
		t.Fatalf("certificate SANs = %v", leaf.IPAddresses)
	}

	duplicate := httptest.NewRecorder()
	service.ServeHTTP(duplicate, request)
	if duplicate.Code != http.StatusPermanentRedirect || reloads.Load() != 1 {
		t.Fatalf("duplicate status=%d reloads=%d", duplicate.Code, reloads.Load())
	}
}

// 验证混合模式下预配置 IP 无需写入学习状态即可跳转，未知 IP 仍能动态签发。
func TestBootstrapMixedModeUsesConfiguredAndLearnedIPs(t *testing.T) {
	service, manager := createTestBootstrapService(t)
	service.mode = "mixed"
	service.configuredIPs = []string{"192.168.1.20"}
	configuredNames, err := namesForIPHosts(service.configuredIPs, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := manager.ensureCertificate(configuredNames, false); err != nil {
		t.Fatal(err)
	}

	configuredRequest := httptest.NewRequest(http.MethodGet, "http://192.168.1.20/", nil)
	configuredRequest.Host = "192.168.1.20"
	configuredResponse := httptest.NewRecorder()
	service.ServeHTTP(configuredResponse, configuredRequest)
	if configuredResponse.Code != http.StatusPermanentRedirect {
		t.Fatalf("configured status = %d", configuredResponse.Code)
	}
	if _, err := os.Stat(service.statePath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("configured IP was unexpectedly persisted: %v", err)
	}

	learnedRequest := httptest.NewRequest(http.MethodGet, "http://192.168.1.21/", nil)
	learnedRequest.Host = "192.168.1.21"
	learnedResponse := httptest.NewRecorder()
	service.ServeHTTP(learnedResponse, learnedRequest)
	if learnedResponse.Code != http.StatusPermanentRedirect {
		t.Fatalf("learned status = %d, body = %s", learnedResponse.Code, learnedResponse.Body.String())
	}
	leaf := readLeaf(t, manager.outputCertPath)
	for _, address := range []string{"192.168.1.20", "192.168.1.21"} {
		if !containsString(stringsForIPs(leaf.IPAddresses), address) {
			t.Fatalf("certificate SANs %v do not contain %s", leaf.IPAddresses, address)
		}
	}
}

// 验证动态 IP 热加载时继续向 Caddy 传递外部证书覆盖的域名和全部 IP 地址。
func TestCaddyReloaderRetainsDomainAndIPHosts(t *testing.T) {
	directory := t.TempDir()
	outputPath := filepath.Join(directory, "reload-environment")
	commandPath := filepath.Join(directory, "capture-reload.sh")
	command := "#!/bin/sh\nprintf '%s\\n%s\\n' \"$CADDY_ALLOWED_HOSTS\" \"$CADDY_HTTPS_SITE_ADDRESSES\" > \"$CADDY_TEST_RELOAD_OUTPUT\"\n"
	if err := os.WriteFile(commandPath, []byte(command), 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("CADDY_TEST_RELOAD_OUTPUT", outputPath)
	names, err := parseCertificateNames("localhost 127.0.0.1 192.168.1.20")
	if err != nil {
		t.Fatal(err)
	}
	reload := newCaddyReloader(commandPath, "/tmp/Caddyfile", "127.0.0.1:2019", []string{"ai.example.com"})
	if err := reload(names); err != nil {
		t.Fatalf("reload: %v", err)
	}
	lines := strings.Split(strings.TrimSpace(readFileText(t, outputPath)), "\n")
	if got, want := lines[0], "ai.example.com localhost 127.0.0.1 192.168.1.20"; got != want {
		t.Fatalf("allowed hosts = %q, want %q", got, want)
	}
	if got, want := lines[1], "https://ai.example.com, https://localhost, https://127.0.0.1, https://192.168.1.20"; got != want {
		t.Fatalf("HTTPS sites = %q, want %q", got, want)
	}
}

// 验证同一新 IP 的并发首次访问被合并为一次签发和热加载。
func TestBootstrapCoalescesConcurrentRequests(t *testing.T) {
	service, _ := createTestBootstrapService(t)
	var reloads atomic.Int32
	service.reload = func(certificateNames) error {
		reloads.Add(1)
		return nil
	}
	var wait sync.WaitGroup
	statuses := make(chan int, 12)
	for index := 0; index < 12; index++ {
		wait.Add(1)
		go func() {
			defer wait.Done()
			request := httptest.NewRequest(http.MethodGet, "http://10.0.0.25:81/", nil)
			request.Host = "10.0.0.25:81"
			response := httptest.NewRecorder()
			service.ServeHTTP(response, request)
			statuses <- response.Code
		}()
	}
	wait.Wait()
	close(statuses)
	for status := range statuses {
		if status != http.StatusPermanentRedirect {
			t.Fatalf("concurrent status = %d", status)
		}
	}
	if reloads.Load() != 1 {
		t.Fatalf("reload count = %d, want 1", reloads.Load())
	}
}

// 验证 reload 失败时恢复原证书和学习状态，且不会跳转到不可用 HTTPS。
func TestBootstrapRollsBackReloadFailure(t *testing.T) {
	service, manager := createTestBootstrapService(t)
	before := certificateFingerprint(readLeaf(t, manager.outputCertPath))
	service.reload = func(certificateNames) error { return errors.New("admin unavailable") }
	request := httptest.NewRequest(http.MethodGet, "http://203.0.113.10:81/", nil)
	request.Host = "203.0.113.10:81"
	response := httptest.NewRecorder()
	service.ServeHTTP(response, request)
	if response.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", response.Code)
	}
	if _, err := os.Stat(service.statePath); !errors.Is(err, os.ErrNotExist) {
		t.Fatalf("learned state was not rolled back: %v", err)
	}
	if after := certificateFingerprint(readLeaf(t, manager.outputCertPath)); after != before {
		t.Fatalf("certificate fingerprint changed after rollback: %s != %s", after, before)
	}
}

// 验证域名模式、非法 Host、非安全方法和地址上限均不会签发证书。
func TestBootstrapRejectsUnsafeRequestsAndLimit(t *testing.T) {
	service, _ := createTestBootstrapService(t)
	for _, input := range []struct {
		method string
		host   string
		status int
	}{
		{http.MethodGet, "example.com", http.StatusNotFound},
		{http.MethodPost, "192.168.1.20", http.StatusMethodNotAllowed},
	} {
		request := httptest.NewRequest(input.method, "http://service/", nil)
		request.Host = input.host
		response := httptest.NewRecorder()
		service.ServeHTTP(response, request)
		if response.Code != input.status {
			t.Fatalf("%s %s status=%d, want %d", input.method, input.host, response.Code, input.status)
		}
	}
	service.mode = "domain"
	domainRequest := httptest.NewRequest(http.MethodGet, "http://192.168.1.20/", nil)
	domainRequest.Host = "192.168.1.20"
	domainResponse := httptest.NewRecorder()
	service.ServeHTTP(domainResponse, domainRequest)
	if domainResponse.Code != http.StatusNotFound {
		t.Fatalf("domain mode status = %d", domainResponse.Code)
	}

	service.mode = "ip"
	service.maxLearnedHosts = 1
	if err := writeLearnedHosts(service.statePath, []string{"10.0.0.1"}); err != nil {
		t.Fatal(err)
	}
	limitRequest := httptest.NewRequest(http.MethodGet, "http://10.0.0.2/", nil)
	limitRequest.Host = "10.0.0.2"
	limitResponse := httptest.NewRecorder()
	service.ServeHTTP(limitResponse, limitRequest)
	if limitResponse.Code != http.StatusTooManyRequests {
		t.Fatalf("limit status = %d, want 429", limitResponse.Code)
	}
}

// 创建包含测试 CA、初始回环证书和隔离状态文件的引导服务。
func createTestBootstrapService(t *testing.T) (*bootstrapService, certificateManager) {
	t.Helper()
	directory := t.TempDir()
	current := time.Date(2026, 8, 6, 5, 0, 0, 0, time.UTC)
	paths := createTestAuthority(t, directory, current)
	manager := certificateManager{
		rootCertPath:         paths.rootCert,
		intermediateCertPath: paths.intermediateCert,
		intermediateKeyPath:  paths.intermediateKey,
		outputCertPath:       filepath.Join(directory, "out", "fullchain.pem"),
		outputKeyPath:        filepath.Join(directory, "out", "privkey.pem"),
		outputBundlePath:     filepath.Join(directory, "bundles", "ip-internal.pem"),
		lifetime:             30 * 24 * time.Hour,
		renewBefore:          48 * time.Hour,
		now:                  func() time.Time { return current },
	}
	baseNames, _ := namesForIPHosts(nil, nil)
	if changed, err := manager.ensureCertificate(baseNames, false); err != nil || !changed {
		t.Fatalf("create initial certificate changed=%v err=%v", changed, err)
	}
	service := &bootstrapService{
		mode:             "ip",
		statePath:        filepath.Join(directory, "out", "learned-hosts"),
		httpsPortSuffix:  ":444",
		maxLearnedHosts:  32,
		minIssueInterval: 0,
		now:              func() time.Time { return current },
		manager:          manager,
		reload:           func(certificateNames) error { return nil },
	}
	return service, manager
}

// 将 IP 切片转为字符串，简化 SAN 断言。
func stringsForIPs(values []net.IP) []string {
	result := make([]string, len(values))
	for index, value := range values {
		result[index] = value.String()
	}
	return result
}

// 读取测试文件文本并在失败时终止当前用例。
func readFileText(t *testing.T, path string) string {
	t.Helper()
	data, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	return string(data)
}

// 验证证书首次签发、稳定复用、强制续期、到期续期和 SAN 变化续期。
func TestCertificateLifecycle(t *testing.T) {
	directory := t.TempDir()
	current := time.Date(2026, 8, 6, 5, 0, 0, 0, time.UTC)
	paths := createTestAuthority(t, directory, current)
	manager := certificateManager{
		rootCertPath:         paths.rootCert,
		intermediateCertPath: paths.intermediateCert,
		intermediateKeyPath:  paths.intermediateKey,
		outputCertPath:       filepath.Join(directory, "out", "fullchain.pem"),
		outputKeyPath:        filepath.Join(directory, "out", "privkey.pem"),
		outputBundlePath:     filepath.Join(directory, "bundles", "ip-internal.pem"),
		lifetime:             30 * 24 * time.Hour,
		renewBefore:          48 * time.Hour,
		now:                  func() time.Time { return current },
	}
	names, _ := parseCertificateNames("localhost 203.0.113.10 192.168.1.10")

	changed, err := manager.ensureCertificate(names, false)
	if err != nil || !changed {
		t.Fatalf("initial certificate changed=%v err=%v", changed, err)
	}
	first := readLeaf(t, manager.outputCertPath)
	if bundle, readErr := os.ReadFile(manager.outputBundlePath); readErr != nil || !bytes.Contains(bundle, []byte("PRIVATE KEY")) {
		t.Fatalf("IP certificate bundle missing key: err=%v", readErr)
	}
	if !equalStringSets(first.DNSNames, names.dnsNames) || !equalIPSets(first.IPAddresses, names.ipAddresses) {
		t.Fatalf("initial SANs = DNS %v IP %v, want DNS %v IP %v", first.DNSNames, first.IPAddresses, names.dnsNames, names.ipAddresses)
	}

	changed, err = manager.ensureCertificate(names, false)
	if err != nil || changed {
		t.Fatalf("stable certificate changed=%v err=%v", changed, err)
	}

	changed, err = manager.ensureCertificate(names, true)
	if err != nil || !changed {
		t.Fatalf("forced certificate changed=%v err=%v", changed, err)
	}
	forced := readLeaf(t, manager.outputCertPath)
	if certificateFingerprint(first) == certificateFingerprint(forced) {
		t.Fatal("forced renewal reused the previous leaf certificate")
	}

	current = current.Add(29 * 24 * time.Hour)
	changed, err = manager.ensureCertificate(names, false)
	if err != nil || !changed {
		t.Fatalf("expiring certificate changed=%v err=%v", changed, err)
	}

	loopbackOnly, _ := parseCertificateNames("localhost 127.0.0.1")
	changed, err = manager.ensureCertificate(loopbackOnly, false)
	if err != nil || !changed {
		t.Fatalf("changed SAN certificate changed=%v err=%v", changed, err)
	}
	if leaf := readLeaf(t, manager.outputCertPath); !equalStringSets(leaf.DNSNames, loopbackOnly.dnsNames) || !equalIPSets(leaf.IPAddresses, loopbackOnly.ipAddresses) {
		t.Fatalf("renewed SANs = DNS %v IP %v, want DNS %v IP %v", leaf.DNSNames, leaf.IPAddresses, loopbackOnly.dnsNames, loopbackOnly.ipAddresses)
	}
}

// 验证 Caddy 中间 CA 轮换后叶证书随即使用新签发者。
func TestIntermediateRotationRenewsCertificate(t *testing.T) {
	directory := t.TempDir()
	current := time.Date(2026, 8, 6, 5, 0, 0, 0, time.UTC)
	paths := createTestAuthority(t, directory, current)
	manager := certificateManager{
		rootCertPath:         paths.rootCert,
		intermediateCertPath: paths.intermediateCert,
		intermediateKeyPath:  paths.intermediateKey,
		outputCertPath:       filepath.Join(directory, "out", "fullchain.pem"),
		outputKeyPath:        filepath.Join(directory, "out", "privkey.pem"),
		outputBundlePath:     filepath.Join(directory, "bundles", "ip-internal.pem"),
		lifetime:             30 * 24 * time.Hour,
		renewBefore:          48 * time.Hour,
		now:                  func() time.Time { return current },
	}
	names, _ := parseCertificateNames("localhost 127.0.0.1 127.0.0.2")
	if changed, err := manager.ensureCertificate(names, false); err != nil || !changed {
		t.Fatalf("initial certificate changed=%v err=%v", changed, err)
	}
	firstChain, _ := readCertificates(manager.outputCertPath)
	root, _, _ := readFirstCertificate(paths.rootCert)
	rootKey, _ := readPrivateKey(paths.rootKey)
	writeIntermediate(t, paths, root, rootKey, current.Add(time.Hour))

	changed, err := manager.ensureCertificate(names, false)
	if err != nil || !changed {
		t.Fatalf("rotated issuer certificate changed=%v err=%v", changed, err)
	}
	secondChain, _ := readCertificates(manager.outputCertPath)
	if certificateFingerprint(firstChain[1]) == certificateFingerprint(secondChain[1]) {
		t.Fatal("leaf chain retained the previous intermediate certificate")
	}
}

type testAuthorityPaths struct {
	rootCert         string
	rootKey          string
	intermediateCert string
	intermediateKey  string
}

// 创建覆盖指定 DNS 名称的短期自签测试证书与匹配私钥。
func createTestDomainKeyPair(t *testing.T, root, name string, domains []string) {
	t.Helper()
	directory := filepath.Join(root, name)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		t.Fatal(err)
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 8, 6, 8, 0, 0, 0, time.UTC)
	template := &x509.Certificate{
		SerialNumber: big.NewInt(now.UnixNano() + int64(len(domains))),
		Subject:      pkix.Name{CommonName: domains[0]},
		DNSNames:     domains,
		NotBefore:    now.Add(-time.Hour),
		NotAfter:     now.Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	certificateDER, err := x509.CreateCertificate(rand.Reader, template, template, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	writeCertificateAndKey(
		t,
		filepath.Join(directory, "fullchain.pem"),
		filepath.Join(directory, "privkey.pem"),
		certificateDER,
		key,
	)
}

// 创建仅供单元测试使用的根 CA 和中间 CA 文件。
func createTestAuthority(t *testing.T, directory string, now time.Time) testAuthorityPaths {
	t.Helper()
	paths := testAuthorityPaths{
		rootCert:         filepath.Join(directory, "root.crt"),
		rootKey:          filepath.Join(directory, "root.key"),
		intermediateCert: filepath.Join(directory, "intermediate.crt"),
		intermediateKey:  filepath.Join(directory, "intermediate.key"),
	}
	rootKey, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	rootTemplate := &x509.Certificate{
		SerialNumber:          big.NewInt(1),
		Subject:               pkix.Name{CommonName: "Test Root"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(500 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLen:            1,
		SubjectKeyId:          []byte("test-root-key-id"),
	}
	rootDER, err := x509.CreateCertificate(rand.Reader, rootTemplate, rootTemplate, &rootKey.PublicKey, rootKey)
	if err != nil {
		t.Fatal(err)
	}
	writeCertificateAndKey(t, paths.rootCert, paths.rootKey, rootDER, rootKey)
	root, _, _ := readFirstCertificate(paths.rootCert)
	writeIntermediate(t, paths, root, rootKey, now)
	return paths
}

// 使用指定根 CA 写入新的测试中间 CA，模拟 Caddy 的中间证书轮换。
func writeIntermediate(t *testing.T, paths testAuthorityPaths, root *x509.Certificate, rootKey crypto.Signer, now time.Time) {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	template := &x509.Certificate{
		SerialNumber:          big.NewInt(now.UnixNano()),
		Subject:               pkix.Name{CommonName: "Test Intermediate"},
		NotBefore:             now.Add(-time.Hour),
		NotAfter:              now.Add(400 * 24 * time.Hour),
		KeyUsage:              x509.KeyUsageCertSign | x509.KeyUsageCRLSign,
		BasicConstraintsValid: true,
		IsCA:                  true,
		MaxPathLenZero:        true,
		SubjectKeyId:          []byte("test-intermediate-" + now.Format(time.RFC3339Nano)),
		AuthorityKeyId:        root.SubjectKeyId,
	}
	certificateDER, err := x509.CreateCertificate(rand.Reader, template, root, &key.PublicKey, rootKey)
	if err != nil {
		t.Fatal(err)
	}
	writeCertificateAndKey(t, paths.intermediateCert, paths.intermediateKey, certificateDER, key)
}

// 将测试 CA 证书与 PKCS#8 私钥写入临时测试目录。
func writeCertificateAndKey(t *testing.T, certificatePath, keyPath string, certificateDER []byte, key crypto.Signer) {
	t.Helper()
	keyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(certificatePath, pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificateDER}), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(keyPath, pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER}), 0o600); err != nil {
		t.Fatal(err)
	}
}

// 读取输出完整链中的叶证书。
func readLeaf(t *testing.T, path string) *x509.Certificate {
	t.Helper()
	chain, err := readCertificates(path)
	if err != nil {
		t.Fatal(err)
	}
	return chain[0]
}
