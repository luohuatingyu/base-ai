package main

import (
	"bytes"
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/sha256"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"flag"
	"fmt"
	"math/big"
	"net"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"time"
)

const (
	defaultRootCert         = "/data/caddy/pki/authorities/local/root.crt"
	defaultIntermediateCert = "/data/caddy/pki/authorities/local/intermediate.crt"
	defaultIntermediateKey  = "/data/caddy/pki/authorities/local/intermediate.key"
	defaultOutputCert       = "/data/base-ai-tls/ip-fullchain.pem"
	defaultOutputKey        = "/data/base-ai-tls/ip-privkey.pem"
)

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

// 主入口解析 IP 列表并确保磁盘上的多 SAN 证书可继续安全使用。
func main() {
	ipsValue := flag.String("ips", "", "comma or whitespace separated IPv4 addresses")
	force := flag.Bool("force", false, "force certificate renewal")
	flag.Parse()

	ips, err := parseIPv4List(*ipsValue)
	if err != nil {
		fatal(err)
	}
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
	changed, err := manager.ensureCertificate(ips, *force)
	if err != nil {
		fatal(err)
	}
	if changed {
		fmt.Println("renewed")
	} else {
		fmt.Println("unchanged")
	}
}

// 输出不含密钥内容的错误并以失败状态退出。
func fatal(err error) {
	fmt.Fprintf(os.Stderr, "IP certificate error: %v\n", err)
	os.Exit(1)
}

// 解析、规范化并去重 IPv4，保持用户配置的首个地址作为默认 SNI。
func parseIPv4List(value string) ([]net.IP, error) {
	parts := strings.FieldsFunc(value, func(r rune) bool {
		return r == ',' || r == ' ' || r == '\t' || r == '\r' || r == '\n'
	})
	if len(parts) == 0 {
		return nil, errors.New("at least one IPv4 address is required")
	}
	seen := make(map[string]struct{}, len(parts))
	ips := make([]net.IP, 0, len(parts))
	for _, part := range parts {
		parsed := net.ParseIP(part)
		if parsed == nil || parsed.To4() == nil || strings.Contains(part, ":") {
			return nil, fmt.Errorf("%q is not a valid IPv4 address", part)
		}
		canonical := parsed.To4().String()
		if canonical != part {
			return nil, fmt.Errorf("%q is not a canonical IPv4 address", part)
		}
		if _, exists := seen[canonical]; exists {
			continue
		}
		seen[canonical] = struct{}{}
		ips = append(ips, parsed.To4())
	}
	return ips, nil
}

// 校验 Caddy CA，复用仍有效的证书，或原子写入新签发的证书与私钥。
func (m certificateManager) ensureCertificate(ips []net.IP, force bool) (bool, error) {
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
	if !force && m.currentCertificateReusable(ips, intermediate, now) {
		return false, nil
	}

	leafPEM, keyPEM, err := createLeafCertificate(ips, intermediate, intermediateKey, now, m.lifetime)
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
func (m certificateManager) currentCertificateReusable(ips []net.IP, intermediate *x509.Certificate, now time.Time) bool {
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
	return equalIPSets(leaf.IPAddresses, ips)
}

// 创建由 Caddy 中间 CA 签发的 ECDSA 叶证书，证书有效期不会超过签发者。
func createLeafCertificate(ips []net.IP, issuer *x509.Certificate, issuerKey crypto.Signer, now time.Time, lifetime time.Duration) ([]byte, []byte, error) {
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
		IPAddresses:           cloneIPs(ips),
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
