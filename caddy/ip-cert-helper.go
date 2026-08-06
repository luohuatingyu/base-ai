package main

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"io"
	"math/big"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"gopkg.in/yaml.v3"
)

const (
	defaultRootCert         = "/data/caddy/pki/authorities/local/root.crt"
	defaultIntermediateCert = "/data/caddy/pki/authorities/local/intermediate.crt"
	defaultIntermediateKey  = "/data/caddy/pki/authorities/local/intermediate.key"
	defaultOutputCert       = "/data/base-ai-tls/ip-fullchain.pem"
	defaultOutputKey        = "/data/base-ai-tls/ip-privkey.pem"
	defaultLearnedHosts     = "/data/base-ai-tls/learned-hosts"
	defaultBootstrapListen  = "127.0.0.1:2020"
	defaultCaddyCommand     = "/usr/bin/caddy"
	defaultCaddyConfig      = "/etc/caddy/Caddyfile"
	defaultCaddyAdmin       = "127.0.0.1:2019"
	defaultMaxLearnedHosts  = 32
	defaultHTTPSTLSRoot     = "/etc/caddy/tls"
	defaultHTTPSBundleDir   = "/tmp/base-ai-https-tls"
	maxHTTPSitesConfigBytes = 64 * 1024
	maxConfiguredSites      = 64
	maxConfiguredDomains    = 256
	maxTLSFileBytes         = 4 * 1024 * 1024
)

type yamlString string

type httpsSitesFileConfig struct {
	Sites []httpsSiteFileEntry `yaml:"sites"`
}

type httpsSiteFileEntry struct {
	Domains     []yamlString `yaml:"domains"`
	TLSCertFile yamlString   `yaml:"tls_cert_file"`
	TLSKeyFile  yamlString   `yaml:"tls_key_file"`
}

type httpsSiteConfig struct {
	domains     []string
	tlsCertFile string
	tlsKeyFile  string
}

type certificateManager struct {
	rootCertPath         string
	intermediateCertPath string
	intermediateKeyPath  string
	outputCertPath       string
	outputKeyPath        string
	lifetime             time.Duration
	renewBefore          time.Duration
	now                  func() time.Time
}

type certificateNames struct {
	dnsNames    []string
	ipAddresses []net.IP
}

type bootstrapService struct {
	mu               sync.Mutex
	mode             string
	statePath        string
	httpsPortSuffix  string
	maxLearnedHosts  int
	minIssueInterval time.Duration
	lastIssue        time.Time
	now              func() time.Time
	manager          certificateManager
	reload           func(certificateNames) error
}

type fileSnapshot struct {
	data   []byte
	mode   os.FileMode
	exists bool
}

// 主入口解析域名 YAML、回环主机名和 IP 列表，并确保入口证书可继续安全使用。
func main() {
	hostsValue := flag.String("hosts", "", "comma or whitespace separated localhost and IPv4 addresses")
	httpsSitesFile := flag.String("prepare-https-sites", "", "validate HTTPS sites YAML and prepare TLS bundles")
	tlsRoot := flag.String("tls-root", defaultHTTPSTLSRoot, "root directory for HTTPS site certificate paths")
	force := flag.Bool("force", false, "force certificate renewal")
	serve := flag.Bool("serve", false, "serve request-driven IP certificate bootstrap requests")
	resolveLearnedHosts := flag.Bool("resolve-learned-hosts", false, "print validated base and learned hosts")
	mode := flag.String("mode", "ip", "ingress mode: ip or domain")
	listen := flag.String("listen", defaultBootstrapListen, "bootstrap HTTP listen address")
	stateFile := flag.String("state-file", defaultLearnedHosts, "persisted learned IPv4 file")
	httpsPortSuffix := flag.String("https-port-suffix", ":444", "HTTPS redirect port suffix")
	checkInterval := flag.Duration("check-interval", time.Hour, "certificate renewal check interval")
	minIssueInterval := flag.Duration("min-issue-interval", 5*time.Second, "minimum interval between new IP issuances")
	maxLearnedHosts := flag.Int("max-learned-hosts", defaultMaxLearnedHosts, "maximum learned IPv4 addresses")
	caddyCommand := flag.String("caddy-command", defaultCaddyCommand, "Caddy executable used for reloads")
	caddyConfig := flag.String("caddy-config", defaultCaddyConfig, "Caddyfile used for reloads")
	caddyAdmin := flag.String("caddy-admin", defaultCaddyAdmin, "Caddy admin endpoint used for reloads")
	flag.Parse()

	manager := certificateManager{
		rootCertPath:         defaultRootCert,
		intermediateCertPath: defaultIntermediateCert,
		intermediateKeyPath:  defaultIntermediateKey,
		outputCertPath:       defaultOutputCert,
		outputKeyPath:        defaultOutputKey,
		lifetime:             30 * 24 * time.Hour,
		renewBefore:          48 * time.Hour,
		now:                  time.Now,
	}
	if *resolveLearnedHosts {
		learned, err := readLearnedHosts(*stateFile, *maxLearnedHosts)
		if err != nil {
			fatal(err)
		}
		names, err := namesForLearnedHosts(learned)
		if err != nil {
			fatal(err)
		}
		fmt.Println(formatCertificateHosts(names))
		return
	}
	if *httpsSitesFile != "" {
		domains, err := prepareHTTPSSites(*httpsSitesFile, *tlsRoot, defaultHTTPSBundleDir)
		if err != nil {
			fatal(err)
		}
		fmt.Println(strings.Join(domains, " "))
		return
	}
	if *serve {
		if err := runBootstrapServer(bootstrapServerConfig{
			mode: *mode, listen: *listen, statePath: *stateFile,
			httpsPortSuffix: *httpsPortSuffix, checkInterval: *checkInterval,
			minIssueInterval: *minIssueInterval, maxLearnedHosts: *maxLearnedHosts,
			caddyCommand: *caddyCommand, caddyConfig: *caddyConfig, caddyAdmin: *caddyAdmin,
			manager: manager,
		}); err != nil {
			fatal(err)
		}
		return
	}

	names, err := parseCertificateNames(*hostsValue)
	if err != nil {
		fatal(err)
	}
	changed, err := manager.ensureCertificate(names, *force)
	if err != nil {
		fatal(err)
	}
	if changed {
		fmt.Println("renewed")
	} else {
		fmt.Println("unchanged")
	}
}

// 将 YAML 标量严格解析为字符串，禁止数字和布尔值隐式转换并支持锚点别名。
func (value *yamlString) UnmarshalYAML(node *yaml.Node) error {
	aliasDepth := 0
	for node.Kind == yaml.AliasNode && node.Alias != nil {
		node = node.Alias
		aliasDepth++
		if aliasDepth > maxConfiguredDomains {
			return errors.New("HTTPS sites configuration contains an invalid YAML alias cycle")
		}
	}
	if node.Kind != yaml.ScalarNode || node.Tag != "!!str" {
		return errors.New("HTTPS site values must be YAML strings")
	}
	*value = yamlString(node.Value)
	return nil
}

// 读取完整 YAML 语法的 HTTPS 站点分组，并限制字段、数量、路径和文档个数。
func readHTTPSSitesConfig(path string) ([]httpsSiteConfig, error) {
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open HTTPS sites configuration: %w", err)
	}
	defer file.Close()
	info, err := file.Stat()
	if err != nil {
		return nil, fmt.Errorf("inspect HTTPS sites configuration: %w", err)
	}
	if !info.Mode().IsRegular() {
		return nil, errors.New("HTTPS sites configuration must be a regular file")
	}
	if info.Size() > maxHTTPSitesConfigBytes {
		return nil, fmt.Errorf("HTTPS sites configuration exceeds %d bytes", maxHTTPSitesConfigBytes)
	}

	decoder := yaml.NewDecoder(io.LimitReader(file, maxHTTPSitesConfigBytes+1))
	decoder.KnownFields(true)
	config := httpsSitesFileConfig{}
	if err := decoder.Decode(&config); err != nil {
		return nil, fmt.Errorf("parse HTTPS sites configuration: %w", err)
	}
	var extra any
	if err := decoder.Decode(&extra); !errors.Is(err, io.EOF) {
		if err != nil {
			return nil, fmt.Errorf("parse HTTPS sites configuration: %w", err)
		}
		return nil, errors.New("HTTPS sites configuration must contain exactly one YAML document")
	}
	if len(config.Sites) == 0 {
		return nil, errors.New("HTTPS sites configuration must contain at least one site")
	}
	if len(config.Sites) > maxConfiguredSites {
		return nil, fmt.Errorf("HTTPS sites configuration cannot contain more than %d sites", maxConfiguredSites)
	}

	result := make([]httpsSiteConfig, 0, len(config.Sites))
	seenDomains := make(map[string]struct{})
	totalDomains := 0
	for index, entry := range config.Sites {
		values := make([]string, 0, len(entry.Domains))
		for _, domain := range entry.Domains {
			values = append(values, string(domain))
		}
		domains, normalizeErr := normalizeDomains(values)
		if normalizeErr != nil {
			return nil, fmt.Errorf("HTTPS site %d: %w", index+1, normalizeErr)
		}
		totalDomains += len(domains)
		if totalDomains > maxConfiguredDomains {
			return nil, fmt.Errorf("HTTPS sites configuration cannot contain more than %d total domains", maxConfiguredDomains)
		}
		for _, domain := range domains {
			if _, exists := seenDomains[domain]; exists {
				return nil, fmt.Errorf("domain %q is configured in more than one HTTPS site", domain)
			}
			seenDomains[domain] = struct{}{}
		}
		certFile, pathErr := normalizeTLSRelativePath(string(entry.TLSCertFile))
		if pathErr != nil {
			return nil, fmt.Errorf("HTTPS site %d tls_cert_file: %w", index+1, pathErr)
		}
		keyFile, pathErr := normalizeTLSRelativePath(string(entry.TLSKeyFile))
		if pathErr != nil {
			return nil, fmt.Errorf("HTTPS site %d tls_key_file: %w", index+1, pathErr)
		}
		if certFile == keyFile {
			return nil, fmt.Errorf("HTTPS site %d certificate and key paths must differ", index+1)
		}
		result = append(result, httpsSiteConfig{domains: domains, tlsCertFile: certFile, tlsKeyFile: keyFile})
	}
	return result, nil
}

// 规范化证书根目录下的相对路径，禁止绝对路径和父目录逃逸。
func normalizeTLSRelativePath(value string) (string, error) {
	path := strings.TrimSpace(value)
	if path == "" {
		return "", errors.New("path must not be empty")
	}
	if filepath.IsAbs(path) || !filepath.IsLocal(path) || strings.Contains(path, "\\") {
		return "", errors.New("path must stay relative to TLS_CERTS_DIR")
	}
	return filepath.Clean(path), nil
}

// 校验证书分组并生成供 Caddy 按 SNI 选择的临时证书私钥合并包。
func prepareHTTPSSites(configPath, tlsRoot, bundleDir string) ([]string, error) {
	sites, err := readHTTPSSitesConfig(configPath)
	if err != nil {
		return nil, err
	}
	hosts := make([]string, 0)
	bundles := make([][]byte, 0, len(sites))
	for index, site := range sites {
		certificatePEM, readErr := readTLSRootFile(tlsRoot, site.tlsCertFile)
		if readErr != nil {
			return nil, fmt.Errorf("HTTPS site %d certificate: %w", index+1, readErr)
		}
		keyPEM, readErr := readTLSRootFile(tlsRoot, site.tlsKeyFile)
		if readErr != nil {
			return nil, fmt.Errorf("HTTPS site %d private key: %w", index+1, readErr)
		}
		keyPair, pairErr := tls.X509KeyPair(certificatePEM, keyPEM)
		if pairErr != nil {
			return nil, fmt.Errorf("HTTPS site %d certificate and key: %w", index+1, pairErr)
		}
		leaf, parseErr := x509.ParseCertificate(keyPair.Certificate[0])
		if parseErr != nil {
			return nil, fmt.Errorf("HTTPS site %d leaf certificate: %w", index+1, parseErr)
		}
		for _, domain := range site.domains {
			if verifyErr := leaf.VerifyHostname(domain); verifyErr != nil {
				return nil, fmt.Errorf("HTTPS site %d certificate does not cover %q: %w", index+1, domain, verifyErr)
			}
		}
		bundle := append([]byte{}, bytes.TrimSpace(certificatePEM)...)
		bundle = append(bundle, '\n')
		bundle = append(bundle, bytes.TrimSpace(keyPEM)...)
		bundle = append(bundle, '\n')
		bundles = append(bundles, bundle)
		hosts = append(hosts, site.domains...)
	}
	if err := writeTLSBundles(bundleDir, bundles); err != nil {
		return nil, err
	}
	return hosts, nil
}

// 在已挂载证书根目录内解析真实路径，阻止符号链接逃逸并限制文件类型和大小。
func readTLSRootFile(root, relativePath string) ([]byte, error) {
	resolvedRoot, err := filepath.EvalSymlinks(root)
	if err != nil {
		return nil, fmt.Errorf("resolve TLS_CERTS_DIR: %w", err)
	}
	rootInfo, err := os.Stat(resolvedRoot)
	if err != nil || !rootInfo.IsDir() {
		return nil, errors.New("TLS_CERTS_DIR must be a readable directory")
	}
	resolvedPath, err := filepath.EvalSymlinks(filepath.Join(resolvedRoot, relativePath))
	if err != nil {
		return nil, fmt.Errorf("resolve %q: %w", relativePath, err)
	}
	relativeToRoot, err := filepath.Rel(resolvedRoot, resolvedPath)
	if err != nil || !filepath.IsLocal(relativeToRoot) {
		return nil, fmt.Errorf("path %q escapes TLS_CERTS_DIR", relativePath)
	}
	info, err := os.Stat(resolvedPath)
	if err != nil || !info.Mode().IsRegular() {
		return nil, fmt.Errorf("path %q must be a readable regular file", relativePath)
	}
	if info.Size() == 0 || info.Size() > maxTLSFileBytes {
		return nil, fmt.Errorf("path %q must contain between 1 and %d bytes", relativePath, maxTLSFileBytes)
	}
	contents, err := os.ReadFile(resolvedPath)
	if err != nil {
		return nil, fmt.Errorf("read %q: %w", relativePath, err)
	}
	return contents, nil
}

// 清理旧临时包并以最小权限写入本次验证通过的全部证书包。
func writeTLSBundles(directory string, bundles [][]byte) error {
	if err := os.RemoveAll(directory); err != nil {
		return fmt.Errorf("clear HTTPS TLS bundle directory: %w", err)
	}
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return fmt.Errorf("create HTTPS TLS bundle directory: %w", err)
	}
	for index, bundle := range bundles {
		path := filepath.Join(directory, fmt.Sprintf("site-%03d.pem", index+1))
		if err := os.WriteFile(path, bundle, 0o600); err != nil {
			return fmt.Errorf("write HTTPS TLS bundle %d: %w", index+1, err)
		}
	}
	return nil
}

// 规范化并去重域名，确保生成的 Caddy 地址和 Host 匹配值不含配置注入内容。
func normalizeDomains(values []string) ([]string, error) {
	if len(values) == 0 {
		return nil, errors.New("domain configuration must contain at least one domain")
	}
	if len(values) > maxConfiguredDomains {
		return nil, fmt.Errorf("domain configuration cannot contain more than %d domains", maxConfiguredDomains)
	}
	result := make([]string, 0, len(values))
	seen := make(map[string]struct{}, len(values))
	for _, value := range values {
		domain := strings.ToLower(strings.TrimSpace(value))
		if !isDNSDomain(domain) {
			return nil, fmt.Errorf("domain configuration contains invalid DNS name %q", value)
		}
		if _, exists := seen[domain]; exists {
			continue
		}
		seen[domain] = struct{}{}
		result = append(result, domain)
	}
	return result, nil
}

// 校验不含协议、端口、路径或通配符的单个 ASCII DNS 主机名。
func isDNSDomain(domain string) bool {
	if domain == "" || len(domain) > 253 || strings.HasPrefix(domain, ".") || strings.HasSuffix(domain, ".") || strings.Contains(domain, "..") {
		return false
	}
	for _, character := range domain {
		if (character < 'a' || character > 'z') && (character < '0' || character > '9') && character != '.' && character != '-' {
			return false
		}
	}
	for _, label := range strings.Split(domain, ".") {
		if label == "" || len(label) > 63 || strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
			return false
		}
	}
	return true
}

type bootstrapServerConfig struct {
	mode             string
	listen           string
	statePath        string
	httpsPortSuffix  string
	checkInterval    time.Duration
	minIssueInterval time.Duration
	maxLearnedHosts  int
	caddyCommand     string
	caddyConfig      string
	caddyAdmin       string
	manager          certificateManager
}

// 输出不含密钥内容的错误并以失败状态退出。
func fatal(err error) {
	fmt.Fprintf(os.Stderr, "Caddy ingress helper error: %v\n", err)
	os.Exit(1)
}

// 启动仅供同容器 Caddy 调用的引导服务，并在 IP 模式定期检查证书续期。
func runBootstrapServer(config bootstrapServerConfig) error {
	if config.mode != "ip" && config.mode != "domain" {
		return errors.New("bootstrap mode must be ip or domain")
	}
	if config.maxLearnedHosts < 1 || config.maxLearnedHosts > 256 {
		return errors.New("max learned hosts must be between 1 and 256")
	}
	if config.minIssueInterval < 0 || config.minIssueInterval > time.Hour {
		return errors.New("minimum issue interval must be between 0 and 1h")
	}
	if config.checkInterval < time.Minute || config.checkInterval > 24*time.Hour {
		return errors.New("certificate check interval must be between 1m and 24h")
	}
	service := &bootstrapService{
		mode:             config.mode,
		statePath:        config.statePath,
		httpsPortSuffix:  config.httpsPortSuffix,
		maxLearnedHosts:  config.maxLearnedHosts,
		minIssueInterval: config.minIssueInterval,
		now:              time.Now,
		manager:          config.manager,
	}
	service.reload = newCaddyReloader(config.caddyCommand, config.caddyConfig, config.caddyAdmin)
	if config.mode == "ip" {
		go service.renewForever(config.checkInterval)
	}
	server := &http.Server{
		Addr:              config.listen,
		Handler:           service,
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       10 * time.Second,
		WriteTimeout:      30 * time.Second,
		IdleTimeout:       30 * time.Second,
	}
	fmt.Printf("event=ip_bootstrap status=listening mode=%s address=%s\n", config.mode, config.listen)
	return server.ListenAndServe()
}

// 仅允许健康检查和安全的 GET/HEAD 引导请求，其他请求不触发证书副作用。
func (s *bootstrapService) ServeHTTP(response http.ResponseWriter, request *http.Request) {
	if request.URL.Path == "/health" {
		response.Header().Set("Content-Type", "text/plain; charset=utf-8")
		response.WriteHeader(http.StatusOK)
		_, _ = response.Write([]byte("OK"))
		return
	}
	if s.mode != "ip" {
		http.NotFound(response, request)
		return
	}
	if request.Method != http.MethodGet && request.Method != http.MethodHead {
		response.Header().Set("Allow", "GET, HEAD")
		http.Error(response, "Method Not Allowed", http.StatusMethodNotAllowed)
		return
	}
	host, err := canonicalBootstrapIPv4(request.Host)
	if err != nil {
		http.NotFound(response, request)
		return
	}
	status, retryAfter, err := s.learnHost(host)
	if retryAfter > 0 {
		response.Header().Set("Retry-After", strconv.Itoa(int(retryAfter.Round(time.Second)/time.Second)))
	}
	if err != nil {
		fmt.Fprintf(os.Stderr, "event=ip_bootstrap status=failed host=%s error=%v\n", host, err)
		http.Error(response, http.StatusText(status), status)
		return
	}
	location := "https://" + host + s.httpsPortSuffix + request.URL.RequestURI()
	response.Header().Set("Location", location)
	response.WriteHeader(http.StatusPermanentRedirect)
}

// 学习一个新地址并事务式更新证书、持久化状态和 Caddy 运行配置。
func (s *bootstrapService) learnHost(host string) (int, time.Duration, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	learned, err := readLearnedHosts(s.statePath, s.maxLearnedHosts)
	if err != nil {
		return http.StatusServiceUnavailable, 0, err
	}
	if containsString(learned, host) || host == "127.0.0.1" {
		return http.StatusPermanentRedirect, 0, nil
	}
	if len(learned) >= s.maxLearnedHosts {
		return http.StatusTooManyRequests, time.Minute, errors.New("learned IP address limit reached")
	}
	now := s.now()
	if wait := s.minIssueInterval - now.Sub(s.lastIssue); !s.lastIssue.IsZero() && wait > 0 {
		return http.StatusTooManyRequests, wait, errors.New("IP certificate issuance is rate limited")
	}

	previousCert, err := snapshotFile(s.manager.outputCertPath)
	if err != nil {
		return http.StatusServiceUnavailable, 0, err
	}
	previousKey, err := snapshotFile(s.manager.outputKeyPath)
	if err != nil {
		return http.StatusServiceUnavailable, 0, err
	}
	previousState, err := snapshotFile(s.statePath)
	if err != nil {
		return http.StatusServiceUnavailable, 0, err
	}
	candidate := append(append([]string(nil), learned...), host)
	names, err := namesForLearnedHosts(candidate)
	if err != nil {
		return http.StatusServiceUnavailable, 0, err
	}
	if _, err := s.manager.ensureCertificate(names, false); err != nil {
		return http.StatusServiceUnavailable, 0, err
	}
	if err := writeLearnedHosts(s.statePath, candidate); err != nil {
		_ = restoreSnapshot(s.manager.outputCertPath, previousCert)
		_ = restoreSnapshot(s.manager.outputKeyPath, previousKey)
		return http.StatusServiceUnavailable, 0, err
	}
	if err := s.reload(names); err != nil {
		rollbackErr := restoreBootstrapState(s.manager, s.statePath, previousCert, previousKey, previousState)
		previousNames, namesErr := namesForLearnedHosts(learned)
		if namesErr == nil {
			_ = s.reload(previousNames)
		}
		if rollbackErr != nil {
			return http.StatusServiceUnavailable, 0, fmt.Errorf("reload Caddy: %v; rollback: %w", err, rollbackErr)
		}
		return http.StatusServiceUnavailable, 0, fmt.Errorf("reload Caddy: %w", err)
	}
	s.lastIssue = now
	fmt.Printf("event=ip_bootstrap status=issued host=%s learned=%d\n", host, len(candidate))
	return http.StatusPermanentRedirect, 0, nil
}

// 定时续期当前地址集合，失败时恢复磁盘证书以便下一个周期重试。
func (s *bootstrapService) renewForever(interval time.Duration) {
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	for range ticker.C {
		if err := s.renewCurrentCertificate(); err != nil {
			fmt.Fprintf(os.Stderr, "event=ip_certificate_renewal status=failed error=%v\n", err)
		}
	}
}

// 检查当前证书是否需要续期，并仅在证书实际变化时热加载。
func (s *bootstrapService) renewCurrentCertificate() error {
	s.mu.Lock()
	defer s.mu.Unlock()
	learned, err := readLearnedHosts(s.statePath, s.maxLearnedHosts)
	if err != nil {
		return err
	}
	names, err := namesForLearnedHosts(learned)
	if err != nil {
		return err
	}
	previousCert, err := snapshotFile(s.manager.outputCertPath)
	if err != nil {
		return err
	}
	previousKey, err := snapshotFile(s.manager.outputKeyPath)
	if err != nil {
		return err
	}
	changed, err := s.manager.ensureCertificate(names, false)
	if err != nil || !changed {
		return err
	}
	if err := s.reload(names); err != nil {
		_ = restoreSnapshot(s.manager.outputCertPath, previousCert)
		_ = restoreSnapshot(s.manager.outputKeyPath, previousKey)
		return fmt.Errorf("reload renewed certificate: %w", err)
	}
	fmt.Printf("event=ip_certificate_renewal status=success hosts=%s\n", formatCertificateHosts(names))
	return nil
}

// 从 HTTP Host 提取规范 IPv4，拒绝 DNS、IPv6和不可路由地址类别。
func canonicalBootstrapIPv4(hostPort string) (string, error) {
	host := hostPort
	if parsedHost, _, err := net.SplitHostPort(hostPort); err == nil {
		host = parsedHost
	} else if strings.Contains(hostPort, ":") {
		return "", errors.New("host must contain a canonical IPv4 address")
	}
	parsed := net.ParseIP(host)
	if parsed == nil || parsed.To4() == nil {
		return "", errors.New("host must contain a canonical IPv4 address")
	}
	canonical := parsed.To4().String()
	if canonical != host {
		return "", errors.New("host must contain a canonical IPv4 address")
	}
	if !parsed.IsGlobalUnicast() && !parsed.IsLoopback() {
		return "", errors.New("host IPv4 address is not usable for service access")
	}
	return canonical, nil
}

// 读取持久化地址并严格校验，避免损坏状态进入证书或 Caddyfile。
func readLearnedHosts(path string, maximum int) ([]string, error) {
	data, err := os.ReadFile(path)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	values := strings.Fields(string(data))
	if len(values) > maximum {
		return nil, errors.New("persisted learned IP address limit exceeded")
	}
	result := make([]string, 0, len(values))
	for _, value := range values {
		canonical, parseErr := canonicalBootstrapIPv4(value)
		if parseErr != nil || canonical == "127.0.0.1" {
			return nil, fmt.Errorf("persisted learned IP address %q is invalid", value)
		}
		if !containsString(result, canonical) {
			result = append(result, canonical)
		}
	}
	return result, nil
}

// 将学习地址逐行原子写入 Caddy 数据卷，不记录请求路径或客户端信息。
func writeLearnedHosts(path string, hosts []string) error {
	content := ""
	if len(hosts) > 0 {
		content = strings.Join(hosts, "\n") + "\n"
	}
	return writeAtomic(path, []byte(content), 0o600)
}

// 组合固定回环主机与持久化地址，保持证书、Host 匹配和站点列表一致。
func namesForLearnedHosts(learned []string) (certificateNames, error) {
	values := append([]string{"localhost", "127.0.0.1"}, learned...)
	return parseCertificateNames(strings.Join(values, " "))
}

// 构造隔离宿主环境的 Caddy reload 调用，只覆盖入口解析所需变量。
func newCaddyReloader(command, config, admin string) func(certificateNames) error {
	return func(names certificateNames) error {
		hosts := formatCertificateHosts(names)
		sites := make([]string, 0, len(names.dnsNames)+len(names.ipAddresses))
		for _, host := range strings.Fields(hosts) {
			sites = append(sites, "https://"+host)
		}
		process := exec.Command(command, "reload", "--force", "--config", config, "--adapter", "caddyfile", "--address", admin)
		process.Env = environmentWithOverrides(map[string]string{
			"CADDY_ALLOWED_HOSTS":        hosts,
			"CADDY_HTTPS_SITE_ADDRESSES": strings.Join(sites, ", "),
			"CADDY_DEFAULT_SNI_OPTION":   "default_sni localhost",
		})
		output, err := process.CombinedOutput()
		if err != nil {
			return fmt.Errorf("%w: %s", err, strings.TrimSpace(string(output)))
		}
		return nil
	}
}

// 覆盖子进程环境变量时移除旧值，避免重复键在不同运行库中解析不一致。
func environmentWithOverrides(overrides map[string]string) []string {
	result := make([]string, 0, len(os.Environ())+len(overrides))
	for _, entry := range os.Environ() {
		key := entry
		if index := strings.IndexByte(entry, '='); index >= 0 {
			key = entry[:index]
		}
		if _, replaced := overrides[key]; !replaced {
			result = append(result, entry)
		}
	}
	for key, value := range overrides {
		result = append(result, key+"="+value)
	}
	return result
}

// 将证书名称转为稳定的空格分隔 Host 列表。
func formatCertificateHosts(names certificateNames) string {
	values := append([]string(nil), names.dnsNames...)
	for _, address := range names.ipAddresses {
		values = append(values, address.String())
	}
	return strings.Join(values, " ")
}

// 保存文件内容和权限，供签发或 reload 失败后的事务回滚使用。
func snapshotFile(path string) (fileSnapshot, error) {
	info, err := os.Stat(path)
	if errors.Is(err, os.ErrNotExist) {
		return fileSnapshot{}, nil
	}
	if err != nil {
		return fileSnapshot{}, err
	}
	data, err := os.ReadFile(path)
	if err != nil {
		return fileSnapshot{}, err
	}
	return fileSnapshot{data: data, mode: info.Mode().Perm(), exists: true}, nil
}

// 原子恢复旧文件；旧状态不存在时删除本次新建文件。
func restoreSnapshot(path string, snapshot fileSnapshot) error {
	if !snapshot.exists {
		if err := os.Remove(path); err != nil && !errors.Is(err, os.ErrNotExist) {
			return err
		}
		return nil
	}
	return writeAtomic(path, snapshot.data, snapshot.mode)
}

// 恢复证书、私钥和学习状态，并合并报告所有恢复错误。
func restoreBootstrapState(manager certificateManager, statePath string, cert, key, state fileSnapshot) error {
	var failures []string
	for path, snapshot := range map[string]fileSnapshot{
		manager.outputCertPath: cert,
		manager.outputKeyPath:  key,
		statePath:              state,
	} {
		if err := restoreSnapshot(path, snapshot); err != nil {
			failures = append(failures, path+": "+err.Error())
		}
	}
	if len(failures) > 0 {
		return errors.New(strings.Join(failures, "; "))
	}
	return nil
}

// 判断字符串集合是否已经包含目标值。
func containsString(values []string, target string) bool {
	for _, value := range values {
		if value == target {
			return true
		}
	}
	return false
}

// 解析、规范化并去重 localhost 和 IPv4，拒绝将任意 DNS 名注入内部证书。
func parseCertificateNames(value string) (certificateNames, error) {
	parts := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == ' ' || r == '\t' || r == '\r' || r == '\n'
	})
	if len(parts) == 0 {
		return certificateNames{}, errors.New("at least one local host name or IPv4 address is required")
	}
	seen := make(map[string]struct{}, len(parts))
	names := certificateNames{}
	for _, part := range parts {
		if _, exists := seen[part]; exists {
			continue
		}
		if part == "localhost" {
			seen[part] = struct{}{}
			names.dnsNames = append(names.dnsNames, part)
			continue
		}
		parsed := net.ParseIP(part)
		if parsed == nil || parsed.To4() == nil || strings.Contains(part, ":") {
			return certificateNames{}, fmt.Errorf("%q is not localhost or a valid IPv4 address", part)
		}
		canonical := parsed.To4().String()
		if canonical != part {
			return certificateNames{}, fmt.Errorf("%q is not a canonical IPv4 address", part)
		}
		seen[canonical] = struct{}{}
		names.ipAddresses = append(names.ipAddresses, parsed.To4())
	}
	return names, nil
}

// 校验 Caddy CA，复用仍有效的证书，或原子写入新签发的证书与私钥。
func (m certificateManager) ensureCertificate(names certificateNames, force bool) (bool, error) {
	root, _, err := readFirstCertificate(m.rootCertPath)
	if err != nil {
		return false, fmt.Errorf("read Caddy root CA: %w", err)
	}
	intermediate, intermediatePEM, err := readFirstCertificate(m.intermediateCertPath)
	if err != nil {
		return false, fmt.Errorf("read Caddy intermediate CA: %w", err)
	}
	intermediateKey, err := readPrivateKey(m.intermediateKeyPath)
	if err != nil {
		return false, fmt.Errorf("read Caddy intermediate key: %w", err)
	}
	if !root.IsCA || !intermediate.IsCA {
		return false, errors.New("Caddy root and intermediate certificates must be certificate authorities")
	}
	if err := intermediate.CheckSignatureFrom(root); err != nil {
		return false, fmt.Errorf("verify Caddy intermediate CA: %w", err)
	}
	if !publicKeysEqual(intermediate.PublicKey, intermediateKey.Public()) {
		return false, errors.New("Caddy intermediate certificate and key do not match")
	}
	now := m.now().UTC()
	if now.Before(intermediate.NotBefore) || !now.Before(intermediate.NotAfter) {
		return false, errors.New("Caddy intermediate CA is not currently valid")
	}
	if !force && m.currentCertificateReusable(names, intermediate, now) {
		return false, nil
	}

	leafPEM, keyPEM, err := createLeafCertificate(names, intermediate, intermediateKey, now, m.lifetime)
	if err != nil {
		return false, err
	}
	fullchain := append(append([]byte{}, leafPEM...), intermediatePEM...)
	if err := writeAtomic(m.outputKeyPath, keyPEM, 0o600); err != nil {
		return false, fmt.Errorf("write IP certificate key: %w", err)
	}
	if err := writeAtomic(m.outputCertPath, fullchain, 0o600); err != nil {
		return false, fmt.Errorf("write IP certificate chain: %w", err)
	}
	return true, nil
}

// 检查现有证书的 SAN、签发者、密钥及剩余有效期是否全部满足复用条件。
func (m certificateManager) currentCertificateReusable(names certificateNames, intermediate *x509.Certificate, now time.Time) bool {
	chain, err := readCertificates(m.outputCertPath)
	if err != nil || len(chain) < 2 {
		return false
	}
	leaf := chain[0]
	key, err := readPrivateKey(m.outputKeyPath)
	if err != nil || !publicKeysEqual(leaf.PublicKey, key.Public()) {
		return false
	}
	if !bytes.Equal(chain[1].Raw, intermediate.Raw) || leaf.CheckSignatureFrom(intermediate) != nil {
		return false
	}
	if now.Before(leaf.NotBefore) || leaf.NotAfter.Sub(now) <= m.renewBefore {
		return false
	}
	return equalStringSets(leaf.DNSNames, names.dnsNames) && equalIPSets(leaf.IPAddresses, names.ipAddresses)
}

// 创建由 Caddy 中间 CA 签发的 ECDSA 叶证书，证书有效期不会超过签发者。
func createLeafCertificate(names certificateNames, issuer *x509.Certificate, issuerKey crypto.Signer, now time.Time, lifetime time.Duration) ([]byte, []byte, error) {
	notAfter := now.Add(lifetime)
	issuerLimit := issuer.NotAfter.Add(-time.Hour)
	if issuerLimit.Before(notAfter) {
		notAfter = issuerLimit
	}
	if !notAfter.After(now.Add(time.Hour)) {
		return nil, nil, errors.New("Caddy intermediate CA expires too soon to issue an IP certificate")
	}
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		return nil, nil, fmt.Errorf("generate IP certificate key: %w", err)
	}
	serialLimit := new(big.Int).Lsh(big.NewInt(1), 128)
	serial, err := rand.Int(rand.Reader, serialLimit)
	if err != nil {
		return nil, nil, fmt.Errorf("generate IP certificate serial: %w", err)
	}
	publicKeyDER, err := x509.MarshalPKIXPublicKey(&key.PublicKey)
	if err != nil {
		return nil, nil, fmt.Errorf("encode IP certificate public key: %w", err)
	}
	keyID := sha256.Sum256(publicKeyDER)
	template := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{Organization: []string{"Base AI"}},
		NotBefore:             now.Add(-5 * time.Minute),
		NotAfter:              notAfter,
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
		BasicConstraintsValid: true,
		SubjectKeyId:          keyID[:20],
		AuthorityKeyId:        issuer.SubjectKeyId,
		DNSNames:              append([]string(nil), names.dnsNames...),
		IPAddresses:           cloneIPs(names.ipAddresses),
	}
	certificateDER, err := x509.CreateCertificate(rand.Reader, template, issuer, &key.PublicKey, issuerKey)
	if err != nil {
		return nil, nil, fmt.Errorf("sign IP certificate: %w", err)
	}
	privateKeyDER, err := x509.MarshalPKCS8PrivateKey(key)
	if err != nil {
		return nil, nil, fmt.Errorf("encode IP certificate key: %w", err)
	}
	certificatePEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificateDER})
	privateKeyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: privateKeyDER})
	return certificatePEM, privateKeyPEM, nil
}

// 读取文件内全部 PEM 证书，供完整链和当前签发者一致性校验使用。
func readCertificates(path string) ([]*x509.Certificate, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var certificates []*x509.Certificate
	for len(data) > 0 {
		block, rest := pem.Decode(data)
		if block == nil {
			break
		}
		data = rest
		if block.Type != "CERTIFICATE" {
			continue
		}
		certificate, parseErr := x509.ParseCertificate(block.Bytes)
		if parseErr != nil {
			return nil, parseErr
		}
		certificates = append(certificates, certificate)
	}
	if len(certificates) == 0 {
		return nil, errors.New("no PEM certificate found")
	}
	return certificates, nil
}

// 读取首张证书并返回规范 PEM，避免将文件中的无关块写入服务链。
func readFirstCertificate(path string) (*x509.Certificate, []byte, error) {
	certificates, err := readCertificates(path)
	if err != nil {
		return nil, nil, err
	}
	certificate := certificates[0]
	encoded := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: certificate.Raw})
	return certificate, encoded, nil
}

// 兼容 Caddy 可能使用的 PKCS#8、EC 或 PKCS#1 私钥格式。
func readPrivateKey(path string) (crypto.Signer, error) {
	data, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	block, _ := pem.Decode(data)
	if block == nil {
		return nil, errors.New("no PEM private key found")
	}
	if key, parseErr := x509.ParsePKCS8PrivateKey(block.Bytes); parseErr == nil {
		if signer, ok := key.(crypto.Signer); ok {
			return signer, nil
		}
	}
	if key, parseErr := x509.ParseECPrivateKey(block.Bytes); parseErr == nil {
		return key, nil
	}
	if key, parseErr := x509.ParsePKCS1PrivateKey(block.Bytes); parseErr == nil {
		return key, nil
	}
	return nil, errors.New("unsupported private key format")
}

// 比较证书和私钥的公钥编码，防止加载不匹配的密钥对。
func publicKeysEqual(left, right any) bool {
	leftDER, leftErr := x509.MarshalPKIXPublicKey(left)
	rightDER, rightErr := x509.MarshalPKIXPublicKey(right)
	return leftErr == nil && rightErr == nil && bytes.Equal(leftDER, rightDER)
}

// 比较 IP SAN 集合，忽略证书编码顺序但不忽略缺失或额外地址。
func equalIPSets(left, right []net.IP) bool {
	if len(left) != len(right) {
		return false
	}
	leftValues := make([]string, len(left))
	rightValues := make([]string, len(right))
	for index := range left {
		leftValues[index] = left[index].String()
	}
	for index := range right {
		rightValues[index] = right[index].String()
	}
	sort.Strings(leftValues)
	sort.Strings(rightValues)
	return strings.Join(leftValues, ",") == strings.Join(rightValues, ",")
}

// 比较 DNS SAN 集合，忽略证书编码顺序但不忽略缺失或额外名称。
func equalStringSets(left, right []string) bool {
	if len(left) != len(right) {
		return false
	}
	leftValues := append([]string(nil), left...)
	rightValues := append([]string(nil), right...)
	sort.Strings(leftValues)
	sort.Strings(rightValues)
	return strings.Join(leftValues, ",") == strings.Join(rightValues, ",")
}

// 深拷贝 IP 切片，避免证书模板持有调用方可变内存。
func cloneIPs(values []net.IP) []net.IP {
	result := make([]net.IP, len(values))
	for index, value := range values {
		result[index] = append(net.IP(nil), value...)
	}
	return result
}

// 原子替换证书文件并固定为仅容器用户可读写的权限。
func writeAtomic(path string, data []byte, mode os.FileMode) error {
	directory := filepath.Dir(path)
	if err := os.MkdirAll(directory, 0o700); err != nil {
		return err
	}
	temporary, err := os.CreateTemp(directory, ".base-ai-tls-*")
	if err != nil {
		return err
	}
	temporaryPath := temporary.Name()
	defer os.Remove(temporaryPath)
	if err := temporary.Chmod(mode); err != nil {
		temporary.Close()
		return err
	}
	if _, err := temporary.Write(data); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Sync(); err != nil {
		temporary.Close()
		return err
	}
	if err := temporary.Close(); err != nil {
		return err
	}
	return os.Rename(temporaryPath, path)
}

// 返回证书指纹，测试和错误定位时不需要暴露证书原文。
func certificateFingerprint(certificate *x509.Certificate) string {
	digest := sha256.Sum256(certificate.Raw)
	return hex.EncodeToString(digest[:])
}
