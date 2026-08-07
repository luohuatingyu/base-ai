package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** 为接口触发器组合 JVM 公共 CA 和项目唯一的 Caddy 根 CA。 */
final class ApiTriggerTlsTrust {
    private static final long MAX_CA_FILE_BYTES = 4L * 1024 * 1024;
    private static final int MAX_CERTIFICATES = 256;

    private final String caddyCaFile;

    ApiTriggerTlsTrust(String caddyCaFile) {
        this.caddyCaFile = text(caddyCaFile);
    }

    /** 每次创建 HTTP 客户端时读取当前 CA，兼容 Caddy 首次启动和根证书轮换。 */
    SSLSocketFactory socketFactory() {
        try {
            List<X509Certificate> additional = loadAdditionalCertificates();
            if (additional.isEmpty()) return null;
            X509TrustManager system = trustManager(null);
            KeyStore customStore = KeyStore.getInstance(KeyStore.getDefaultType());
            customStore.load(null, null);
            for (int index = 0; index < additional.size(); index++) {
                customStore.setCertificateEntry("api-trigger-ca-" + index, additional.get(index));
            }
            X509TrustManager custom = trustManager(customStore);
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, new TrustManager[]{new CompositeTrustManager(system, custom)}, null);
            return context.getSocketFactory();
        } catch (BusinessException exception) {
            throw exception;
        } catch (Exception exception) {
            throw invalidConfiguration();
        }
    }

    /** 读取 Caddy 发布的固定 PEM 根证书文件。 */
    private List<X509Certificate> loadAdditionalCertificates() throws Exception {
        List<Path> files = new ArrayList<>();
        if (!caddyCaFile.isBlank()) {
            Path caddyPath = Path.of(caddyCaFile);
            if (Files.exists(caddyPath, LinkOption.NOFOLLOW_LINKS)) files.add(requireRegularFile(caddyPath));
        }
        List<X509Certificate> certificates = new ArrayList<>();
        CertificateFactory factory = CertificateFactory.getInstance("X.509");
        for (Path path : files) {
            byte[] encoded = Files.readAllBytes(path);
            String pem = new String(encoded, StandardCharsets.US_ASCII);
            if (!pem.contains("-----BEGIN CERTIFICATE-----") || !pem.contains("-----END CERTIFICATE-----")) {
                throw invalidConfiguration();
            }
            try (InputStream input = new ByteArrayInputStream(encoded)) {
                Collection<? extends Certificate> parsed = factory.generateCertificates(input);
                if (parsed.isEmpty()) throw invalidConfiguration();
                for (Certificate certificate : parsed) {
                    if (!(certificate instanceof X509Certificate x509) || x509.getBasicConstraints() < 0) {
                        throw invalidConfiguration();
                    }
                    certificates.add(x509);
                    if (certificates.size() > MAX_CERTIFICATES) {
                        throw invalidConfiguration();
                    }
                }
            }
        }
        return certificates;
    }

    /** 拒绝符号链接、目录和异常大小文件，避免越界读取或资源滥用。 */
    private Path requireRegularFile(Path path) throws Exception {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
            throw invalidConfiguration();
        }
        long size = Files.size(path);
        if (size == 0 || size > MAX_CA_FILE_BYTES) throw invalidConfiguration();
        return path;
    }

    /** 从指定 KeyStore 或 JVM 默认信任库中取得 X509 信任管理器。 */
    private X509TrustManager trustManager(KeyStore keyStore) throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init(keyStore);
        return Arrays.stream(factory.getTrustManagers())
            .filter(X509TrustManager.class::isInstance)
            .map(X509TrustManager.class::cast)
            .findFirst()
            .orElseThrow(this::invalidConfiguration);
    }

    /** 将部署级信任配置错误映射为服务端错误，避免误报为用户请求参数问题。 */
    private BusinessException invalidConfiguration() {
        return new BusinessException(500, "apiTrigger.trustedCaInvalid");
    }

    private String text(String value) { return value == null ? "" : value.trim(); }

    /** 优先接受 JVM 公共 CA，在其拒绝时再尝试部署者配置的附加 CA。 */
    record CompositeTrustManager(X509TrustManager system, X509TrustManager custom)
        implements X509TrustManager {

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, true);
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
            check(chain, authType, false);
        }

        /** 组合两个独立信任库，任一成功即保留兼容性，二者失败才拒绝证书。 */
        private void check(X509Certificate[] chain, String authType, boolean client) throws CertificateException {
            try {
                if (client) system.checkClientTrusted(chain, authType);
                else system.checkServerTrusted(chain, authType);
                return;
            } catch (CertificateException systemFailure) {
                try {
                    if (client) custom.checkClientTrusted(chain, authType);
                    else custom.checkServerTrusted(chain, authType);
                } catch (CertificateException customFailure) {
                    customFailure.addSuppressed(systemFailure);
                    throw customFailure;
                }
            }
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            X509Certificate[] systemIssuers = system.getAcceptedIssuers();
            X509Certificate[] customIssuers = custom.getAcceptedIssuers();
            X509Certificate[] combined = Arrays.copyOf(systemIssuers, systemIssuers.length + customIssuers.length);
            System.arraycopy(customIssuers, 0, combined, systemIssuers.length, customIssuers.length);
            return combined;
        }
    }
}
