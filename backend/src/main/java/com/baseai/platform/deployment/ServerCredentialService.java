package com.baseai.platform.deployment;

import com.baseai.platform.automation.ConfigCryptoService;
import com.baseai.platform.security.AuthContext;
import com.baseai.platform.exception.BusinessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import java.util.List;

/** 管理可复用的服务器账号、RSA 密钥和证书。 */
@Service
public class ServerCredentialService {
    private final JdbcTemplate jdbc; private final ConfigCryptoService crypto;
    public ServerCredentialService(@Qualifier("mysqlJdbcTemplate") JdbcTemplate jdbc, ConfigCryptoService crypto) { this.jdbc=jdbc; this.crypto=crypto; }
    private Long userId(){ return AuthContext.require().id(); }
    public List<CredentialModels.View> list(){ return jdbc.query("SELECT * FROM server_credential WHERE owner_user_id=? AND voided=false ORDER BY id DESC", (r,n)->view(r), userId()); }
    public CredentialModels.View save(Long id, CredentialModels.Command c){
        if(c==null||c.label()==null||c.label().isBlank()) throw new IllegalArgumentException("server.credentialLabelRequired");
        String pk=crypto.encrypt(c.privateKey()), pw=crypto.encrypt(c.password());
        if(id==null) jdbc.update("INSERT INTO server_credential(owner_user_id,label,credential_type,username,public_key,private_key_encrypted,certificate,password_encrypted,enabled) VALUES(?,?,?,?,?,?,?,?,?)",userId(),c.label(),"RSA",c.username(),c.publicKey(),pk,c.certificate(),pw,c.enabled()==null||c.enabled());
        else jdbc.update("UPDATE server_credential SET label=?,username=?,public_key=?,private_key_encrypted=?,certificate=?,password_encrypted=?,enabled=? WHERE id=? AND owner_user_id=? AND voided=false",c.label(),c.username(),c.publicKey(),pk,c.certificate(),pw,c.enabled()==null||c.enabled(),id,userId());
        return id==null?list().get(0):list().stream().filter(v->v.id().equals(id)).findFirst().orElseThrow();
    }
    public void delete(Long id){ jdbc.update("UPDATE server_credential SET voided=true,enabled=false WHERE id=? AND owner_user_id=?",id,userId()); }
    private CredentialModels.View view(java.sql.ResultSet r) throws java.sql.SQLException { return new CredentialModels.View(r.getLong("id"),r.getString("label"),r.getString("credential_type"),r.getString("username"),r.getString("public_key"),r.getString("certificate"),!r.getString("private_key_encrypted").isBlank(),!r.getString("password_encrypted").isBlank(),r.getBoolean("enabled"),r.getTimestamp("created_at").toLocalDateTime(),r.getTimestamp("updated_at").toLocalDateTime()); }
}
