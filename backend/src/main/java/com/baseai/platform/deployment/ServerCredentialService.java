package com.baseai.platform.deployment;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.common.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/** 管理可复用的服务器账号、RSA 密钥和证书。 */
@Service
public class ServerCredentialService {
    private final JdbcTemplate jdbc;
    private final ConfigCryptoService crypto;

    /** 注入共享数据库和平台配置加密服务。 */
    public ServerCredentialService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, ConfigCryptoService crypto) {
        this.jdbc = jdbc;
        this.crypto = crypto;
    }

    /** 管理员可查看全部元数据，普通用户仅能查看自己的凭据。 */
    public List<CredentialModels.View> list() {
        var actor = AuthContext.require();
        return actor.roles().contains("ADMIN")
            ? jdbc.query("SELECT * FROM server_credential WHERE voided=false ORDER BY id DESC", (result, index) -> view(result))
            : jdbc.query("SELECT * FROM server_credential WHERE owner_user_id=? AND voided=false ORDER BY id DESC",
                (result, index) -> view(result), actor.id());
    }

    /** 在行锁内保存凭据，空敏感字段保留原密文，生成键避免并发创建串号。 */
    @Transactional
    public CredentialModels.View save(Long id, CredentialModels.Command command) {
        Long actorId = AuthContext.require().id();
        Stored existing = id == null ? null : require(id, true);
        if (existing != null) authorize(existing.ownerId());
        validate(command);
        boolean enabled = !Boolean.FALSE.equals(command.enabled());
        if (existing != null && !enabled) requireUnused(id);
        String privateKey = secret(command.privateKey(), existing == null ? "" : existing.privateKey());
        String password = secret(command.password(), existing == null ? "" : existing.password());
        String passphrase = secret(command.passphrase(), existing == null ? "" : existing.passphrase());
        boolean keyMode = "KEY".equals(command.type());
        if (keyMode && privateKey.isBlank()) throw new BusinessException("server.privateKeyRequired");
        if (!keyMode && password.isBlank()) throw new BusinessException("server.passwordRequired");
        if (keyMode && (!password.isBlank() || !text(command.certificate()).isBlank())) throw new BusinessException("server.credentialTypeConflict");
        if (!keyMode && (!privateKey.isBlank() || !text(command.publicKey()).isBlank() || !text(command.certificate()).isBlank() || !passphrase.isBlank())) throw new BusinessException("server.credentialTypeConflict");
        if (privateKey.isBlank() && password.isBlank() && text(command.publicKey()).isBlank() && text(command.certificate()).isBlank()) {
            throw new BusinessException("server.credentialEmpty");
        }
        if (!password.isBlank() && text(command.username()).isBlank()) throw new BusinessException("server.sshRequired");
        if (existing == null) {
            GeneratedKeyHolder holder = new GeneratedKeyHolder();
            jdbc.update(connection -> {
                var statement = connection.prepareStatement("""
                    INSERT INTO server_credential(owner_user_id,label,credential_type,username,public_key,
                        private_key_encrypted,certificate,password_encrypted,passphrase_encrypted,enabled)
                    VALUES (?,?,?,?,?,?,?,?,?,?)
                    """, new String[]{"id"});
                statement.setLong(1, actorId);
                statement.setString(2, text(command.label()));
                statement.setString(3, command.type());
                statement.setString(4, text(command.username()));
                statement.setString(5, text(command.publicKey()));
                statement.setString(6, privateKey);
                statement.setString(7, text(command.certificate()));
                statement.setString(8, password);
                statement.setString(9, passphrase);
                statement.setBoolean(10, enabled);
                return statement;
            }, holder);
            id = holder.getKey().longValue();
        } else {
            jdbc.update("""
                UPDATE server_credential SET label=?,username=?,public_key=?,private_key_encrypted=?,
                    certificate=?,password_encrypted=?,passphrase_encrypted=?,enabled=?,updated_at=NOW() WHERE id=?
                """, text(command.label()), text(command.username()), text(command.publicKey()), privateKey,
                text(command.certificate()), password, passphrase, enabled, id);
        }
        return require(id, false).view();
    }

    /** 禁止删除被服务器引用的凭据，避免共享连接突然失效。 */
    @Transactional
    public void delete(Long id) {
        Stored existing = require(id, true);
        authorize(existing.ownerId());
        requireUnused(id);
        jdbc.update("UPDATE server_credential SET voided=true,enabled=false,updated_at=NOW() WHERE id=?", id);
    }

    /** 仅系统管理员可显式查看敏感值，普通列表永不返回密文或明文。 */
    public CredentialModels.Secrets reveal(Long id) {
        if (!AuthContext.require().roles().contains("ADMIN")) throw BusinessException.forbidden("server.accessForbidden");
        return decrypt(require(id, false));
    }

    /** 运行时按服务器所有者解析最新凭据，后台任务不依赖请求线程身份。 */
    CredentialModels.Secrets resolve(Long id, Long ownerId, boolean lock) {
        Stored stored = require(id, lock);
        if (!stored.ownerId().equals(ownerId)) throw BusinessException.forbidden("server.accessForbidden");
        if (!stored.view().enabled()) throw new BusinessException("server.credentialDisabled");
        return decrypt(stored);
    }

    /** 读取凭据并在变更或绑定期间锁定同一行。 */
    private Stored require(Long id, boolean lock) {
        List<Stored> rows = jdbc.query("SELECT * FROM server_credential WHERE id=? AND voided=false" + (lock ? " FOR UPDATE" : ""),
            (result, index) -> new Stored(result.getLong("owner_user_id"), view(result),
                result.getString("private_key_encrypted"), result.getString("password_encrypted"), result.getString("passphrase_encrypted")), id);
        if (rows.isEmpty()) throw BusinessException.notFound("server.credentialNotFound");
        return rows.get(0);
    }

    /** 校验修改权限，管理员与资源所有者可管理记录。 */
    private void authorize(Long ownerId) {
        var actor = AuthContext.require();
        if (!actor.roles().contains("ADMIN") && !actor.id().equals(ownerId)) throw BusinessException.forbidden("server.accessForbidden");
    }

    /** 检查所有未删除服务器的引用，包括停用的服务器。 */
    private void requireUnused(Long id) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM managed_server WHERE credential_id=? AND voided=false", Long.class, id);
        if (count != null && count > 0) throw new BusinessException(409, "server.credentialInUse");
    }

    /** 校验支持的类型、标签、账号和字段长度，避免数据库错误包含原始凭据。 */
    private void validate(CredentialModels.Command command) {
        if (command == null || text(command.label()).isBlank() || text(command.label()).length() > 120) throw new BusinessException("server.credentialLabelRequired");
        if (!"KEY".equals(command.type()) && !"PASSWORD".equals(command.type())) throw new BusinessException("server.credentialTypeInvalid");
        if (!text(command.username()).isBlank() && !text(command.username()).matches("[A-Za-z_][A-Za-z0-9._-]{0,63}")) throw new BusinessException("server.sshRequired");
        for (String value : new String[]{command.publicKey(), command.privateKey(), command.certificate()}) {
            if (value != null && value.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 32768) throw new BusinessException("server.invalid");
        }
        for (String value : new String[]{command.password(), command.passphrase()}) {
            if (value != null && (value.length() > 1024 || value.indexOf('\0') >= 0)) throw new BusinessException("server.invalid");
        }
    }

    /** 留空或掩码表示保留原值，密码内容不做 trim。 */
    private String secret(String value, String existing) {
        return value == null || value.isBlank() || "******".equals(value) ? text(existing) : crypto.encrypt(value);
    }

    /** 解密只供受控 Agent 或显式管理员查看使用的秘密。 */
    private CredentialModels.Secrets decrypt(Stored stored) {
        try {
            return new CredentialModels.Secrets(stored.view().username(), crypto.decrypt(stored.privateKey()),
                crypto.decrypt(stored.password()), crypto.decrypt(stored.passphrase()));
        } catch (Exception exception) {
            throw new BusinessException("server.credentialUnreadable");
        }
    }

    /** 映射列表元数据，敏感字段仅返回是否设置。 */
    private CredentialModels.View view(ResultSet result) throws SQLException {
        return new CredentialModels.View(result.getLong("id"), result.getString("label"), result.getString("credential_type"),
            result.getString("username"), result.getString("public_key"), result.getString("certificate"),
            !text(result.getString("private_key_encrypted")).isBlank(), !text(result.getString("password_encrypted")).isBlank(),
            result.getBoolean("enabled"), result.getTimestamp("created_at").toLocalDateTime(), result.getTimestamp("updated_at").toLocalDateTime(),
            result.getLong("owner_user_id"), !text(result.getString("passphrase_encrypted")).isBlank());
    }

    /** 将可选文本统一为非空字符串。 */
    private String text(String value) { return value == null ? "" : value.trim(); }
    private record Stored(Long ownerId, CredentialModels.View view, String privateKey, String password, String passphrase) { }
}
