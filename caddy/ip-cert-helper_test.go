package main

import (
	"crypto"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/pem"
	"math/big"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// 验证双 IPv4 规范化、顺序保持和重复地址去重。
func TestParseIPv4List(t *testing.T) {
	ips, err := parseIPv4List("203.0.113.10, 192.168.1.10 203.0.113.10")
	if err != nil {
		t.Fatalf("parse valid IPv4 list: %v", err)
	}
	if got, want := len(ips), 2; got != want {
		t.Fatalf("IP count = %d, want %d", got, want)
	}
	if got, want := ips[0].String(), "203.0.113.10"; got != want {
		t.Fatalf("first IP = %s, want %s", got, want)
	}
	if got, want := ips[1].String(), "192.168.1.10"; got != want {
		t.Fatalf("second IP = %s, want %s", got, want)
	}
}

// 验证空值、IPv6、越界值和非规范前导零都被拒绝。
func TestParseIPv4ListRejectsInvalidValues(t *testing.T) {
	for _, value := range []string{"", "2001:db8::1", "192.168.1.256", "192.168.001.10", "host.example"} {
		if _, err := parseIPv4List(value); err == nil {
			t.Fatalf("parseIPv4List(%q) unexpectedly succeeded", value)
		}
	}
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
		lifetime:             30 * 24 * time.Hour,
		renewBefore:          48 * time.Hour,
		now:                  func() time.Time { return current },
	}
	ips, _ := parseIPv4List("203.0.113.10 192.168.1.10")

	changed, err := manager.ensureCertificate(ips, false)
	if err != nil || !changed {
		t.Fatalf("initial certificate changed=%v err=%v", changed, err)
	}
	first := readLeaf(t, manager.outputCertPath)
	if !equalIPSets(first.IPAddresses, ips) {
		t.Fatalf("initial SANs = %v, want %v", first.IPAddresses, ips)
	}

	changed, err = manager.ensureCertificate(ips, false)
	if err != nil || changed {
		t.Fatalf("stable certificate changed=%v err=%v", changed, err)
	}

	changed, err = manager.ensureCertificate(ips, true)
	if err != nil || !changed {
		t.Fatalf("forced certificate changed=%v err=%v", changed, err)
	}
	forced := readLeaf(t, manager.outputCertPath)
	if certificateFingerprint(first) == certificateFingerprint(forced) {
		t.Fatal("forced renewal reused the previous leaf certificate")
	}

	current = current.Add(29 * 24 * time.Hour)
	changed, err = manager.ensureCertificate(ips, false)
	if err != nil || !changed {
		t.Fatalf("expiring certificate changed=%v err=%v", changed, err)
	}

	oneIP, _ := parseIPv4List("192.168.1.10")
	changed, err = manager.ensureCertificate(oneIP, false)
	if err != nil || !changed {
		t.Fatalf("changed SAN certificate changed=%v err=%v", changed, err)
	}
	if leaf := readLeaf(t, manager.outputCertPath); !equalIPSets(leaf.IPAddresses, oneIP) {
		t.Fatalf("renewed SANs = %v, want %v", leaf.IPAddresses, oneIP)
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
		lifetime:             30 * 24 * time.Hour,
		renewBefore:          48 * time.Hour,
		now:                  func() time.Time { return current },
	}
	ips, _ := parseIPv4List("127.0.0.1 127.0.0.2")
	if changed, err := manager.ensureCertificate(ips, false); err != nil || !changed {
		t.Fatalf("initial certificate changed=%v err=%v", changed, err)
	}
	firstChain, _ := readCertificates(manager.outputCertPath)
	root, _, _ := readFirstCertificate(paths.rootCert)
	rootKey, _ := readPrivateKey(paths.rootKey)
	writeIntermediate(t, paths, root, rootKey, current.Add(time.Hour))

	changed, err := manager.ensureCertificate(ips, false)
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
