package com.baseai.platform.automation;

import com.baseai.platform.common.BusinessException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class ApiTriggerTlsTrustTest {
    private static final String END_ENTITY_CERTIFICATE = """
        -----BEGIN CERTIFICATE-----
        MIIC4DCCAcigAwIBAgIJAMenztx4Up9fMA0GCSqGSIb3DQEBCwUAMBQxEjAQBgNV
        BAMMCWxlYWYudGVzdDAeFw0yNjA4MDcwNDA0MzFaFw0zNjA4MDQwNDA0MzFaMBQx
        EjAQBgNVBAMMCWxlYWYudGVzdDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoC
        ggEBAKJLDb8u/uquaG/RantNAHWREFXjeRgzzACu7TAZJNOYckpm396LvytJIVFb
        yacKPbCwdbUJyW32fHfD3Zcu5SiKFtGQYBp+OG++obdQe0uwn7HuunbrwICISxq3
        imbL01OeXmjnILhBKoYx01NQoAG4okwM8sOtoATQO75VQYSj0SNGWg0//gGRIEWl
        6AyX3FBQZeuMqPpEdH1oaUsBYG2Xd4eMeomtMB7vpaJRPwIoD4UFF89wTWvnBicl
        CnELkbcM8V9jrhXT93LmNt5mPIRgW9kYta13+H85pW4mqp/XT7llBncjP1KYRbDR
        IAuZNcMxya7S2ML9Nqk+uchQ2eUCAwEAAaM1MDMwDAYDVR0TAQH/BAIwADAOBgNV
        HQ8BAf8EBAMCBaAwEwYDVR0lBAwwCgYIKwYBBQUHAwEwDQYJKoZIhvcNAQELBQAD
        ggEBAHtgYgZ9SvcVV3Eug8lpdDsSg62+u6UMIr7sGXd95ouMWI8+2Wd0O7rDojxM
        3W6wpIdD6cNDADdT3fpux2y2Y1ijsTs8sXRidg8MdzXVbSg46MNLQbqTlK4aR+WG
        lWqFgwLzrFxC5enNKQUtaA6Ayx92uToOPI75M/VCHxSOcC3yI9o6xyANyI3uTRO5
        o0sh0EeNGkTbQm88I9NwPcljNc7InqgPAGj8pDRH5K5JIi8B1e1CkTlYI+EaPcKJ
        9scImOKLa+kuAWUYYtc3RjZYNU9bw9OsKTyVrIJuYzyK41xRQw4HhYmAPKpFCNc0
        uHo89/EgbwxXZaxq6E0aavavgBk=
        -----END CERTIFICATE-----
        """;

    @TempDir
    Path temporaryDirectory;

    /** 未配置且 Caddy 根证书尚未生成时应继续使用 JVM 默认公共 CA。 */
    @Test
    void usesSystemTrustWhenAdditionalCaIsUnavailable() {
        ApiTriggerTlsTrust trust = new ApiTriggerTlsTrust(temporaryDirectory.resolve("missing.crt").toString());

        assertNull(trust.socketFactory());
    }

    /** Backend 先启动时可暂用系统 CA，Caddy 发布根证书后下一次调用自动加载。 */
    @Test
    void loadsCaddyRootPublishedAfterStartup() throws Exception {
        Path caddyRoot = temporaryDirectory.resolve("root.crt");
        ApiTriggerTlsTrust trust = new ApiTriggerTlsTrust(caddyRoot.toString());
        assertNull(trust.socketFactory());

        writeCa(caddyRoot, defaultTrustManager().getAcceptedIssuers()[0]);

        assertNotNull(trust.socketFactory());
    }

    /** 有效的 Caddy 根 CA 应与系统公共 CA 组合为专用 TLS SocketFactory。 */
    @Test
    void loadsCaddyCaWithoutReplacingSystemTrust() throws Exception {
        X509Certificate systemRoot = defaultTrustManager().getAcceptedIssuers()[0];
        Path caddyRoot = temporaryDirectory.resolve("root.crt");
        writeCa(caddyRoot, systemRoot);

        ApiTriggerTlsTrust trust = new ApiTriggerTlsTrust(caddyRoot.toString());

        assertNotNull(trust.socketFactory());
    }

    /** 损坏的 CA 文件必须失败关闭，不能静默退回为不完整信任配置。 */
    @Test
    void rejectsMalformedConfiguredCa() throws Exception {
        Path caddyRoot = temporaryDirectory.resolve("root.crt");
        Files.writeString(caddyRoot, "not-a-certificate");

        BusinessException exception = assertThrows(BusinessException.class,
            () -> new ApiTriggerTlsTrust(caddyRoot.toString()).socketFactory());

        assertEquals(500, exception.getStatus());
        assertEquals("apiTrigger.trustedCaInvalid", exception.getMessageKey());
    }

    /** 系统 CA 拒绝后应继续尝试附加 CA。 */
    @Test
    void delegatesRejectedCertificateToAdditionalTrust() throws Exception {
        X509TrustManager system = mock(X509TrustManager.class);
        X509TrustManager custom = mock(X509TrustManager.class);
        X509Certificate[] chain = {mock(X509Certificate.class)};
        doThrow(new CertificateException("system rejected")).when(system).checkServerTrusted(chain, "RSA");
        ApiTriggerTlsTrust.CompositeTrustManager combined = new ApiTriggerTlsTrust.CompositeTrustManager(system, custom);

        combined.checkServerTrusted(chain, "RSA");

        verify(custom).checkServerTrusted(chain, "RSA");
    }

    /** Caddy 根证书路径指向目录时必须失败关闭。 */
    @Test
    void rejectsDirectoryAsCaddyRoot() {
        ApiTriggerTlsTrust trust = new ApiTriggerTlsTrust(temporaryDirectory.toString());

        assertInvalid(trust);
    }

    /** Caddy 根证书符号链接必须拒绝，避免越界读取其他路径。 */
    @Test
    void rejectsSymbolicLink() throws Exception {
        Path target = temporaryDirectory.resolve("target.pem");
        Path link = temporaryDirectory.resolve("root.crt");
        writeCa(target, defaultTrustManager().getAcceptedIssuers()[0]);
        Files.createSymbolicLink(link, target.getFileName());

        assertInvalid(new ApiTriggerTlsTrust(link.toString()));
    }

    /** Caddy 根 CA 必须是 PEM，不能静默接受未声明支持的 DER 文件。 */
    @Test
    void rejectsDerEncodedCertificate() throws Exception {
        Path caddyRoot = temporaryDirectory.resolve("root.crt");
        Files.write(caddyRoot, defaultTrustManager().getAcceptedIssuers()[0].getEncoded());

        assertInvalid(new ApiTriggerTlsTrust(caddyRoot.toString()));
    }

    /** 终端服务证书不能被提升为信任锚。 */
    @Test
    void rejectsEndEntityCertificate() throws Exception {
        Path caddyRoot = temporaryDirectory.resolve("root.crt");
        Files.writeString(caddyRoot, END_ENTITY_CERTIFICATE);

        assertInvalid(new ApiTriggerTlsTrust(caddyRoot.toString()));
    }

    /** 单个 CA 文件超过四 MiB 时必须在解析前拒绝。 */
    @Test
    void rejectsOversizedCertificateFile() throws Exception {
        Path caddyRoot = temporaryDirectory.resolve("root.crt");
        Files.write(caddyRoot, new byte[4 * 1024 * 1024 + 1]);

        assertInvalid(new ApiTriggerTlsTrust(caddyRoot.toString()));
    }

    /** PEM bundle 内证书总数必须受限，避免构造超大信任库。 */
    @Test
    void rejectsTooManyCertificatesInBundle() throws Exception {
        String pem = pem(defaultTrustManager().getAcceptedIssuers()[0]);
        Path caddyRoot = temporaryDirectory.resolve("root.crt");
        Files.writeString(caddyRoot, pem.repeat(257));

        assertInvalid(new ApiTriggerTlsTrust(caddyRoot.toString()));
    }

    /** 断言部署级 CA 配置以稳定 500 业务错误失败关闭。 */
    private void assertInvalid(ApiTriggerTlsTrust trust) {
        BusinessException exception = assertThrows(BusinessException.class, trust::socketFactory);
        assertEquals(500, exception.getStatus());
        assertEquals("apiTrigger.trustedCaInvalid", exception.getMessageKey());
    }

    /** 将测试 CA 编码为产品配置要求的 PEM 文件。 */
    private void writeCa(Path path, X509Certificate certificate) throws Exception {
        Files.writeString(path, pem(certificate));
    }

    /** 生成每行 64 字符的标准 PEM 证书文本。 */
    private String pem(X509Certificate certificate) throws Exception {
        String content = Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(certificate.getEncoded());
        return "-----BEGIN CERTIFICATE-----\n" + content + "\n-----END CERTIFICATE-----\n";
    }

    /** 取得 JVM 默认 X509 信任管理器供测试生成有效 CA 输入。 */
    private X509TrustManager defaultTrustManager() throws Exception {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        for (var manager : factory.getTrustManagers()) {
            if (manager instanceof X509TrustManager trustManager) return trustManager;
        }
        throw new IllegalStateException("Default X509 trust manager is unavailable");
    }
}
