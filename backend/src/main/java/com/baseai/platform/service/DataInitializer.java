package com.baseai.platform.service;

import com.baseai.platform.config.PlatformProperties;
import com.baseai.platform.domain.Menu;
import com.baseai.platform.domain.Role;
import com.baseai.platform.domain.UserAccount;
import com.baseai.platform.repository.MenuRepository;
import com.baseai.platform.repository.RoleRepository;
import com.baseai.platform.repository.UserRepository;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashSet;
import java.util.List;

@Component
public class DataInitializer implements ApplicationRunner {
    private final PlatformProperties properties;
    private final MenuRepository menuRepository;
    private final RoleRepository roleRepository;
    private final UserRepository userRepository;
    private final BCryptPasswordEncoder passwordEncoder;

    public DataInitializer(PlatformProperties properties, MenuRepository menuRepository, RoleRepository roleRepository,
                           UserRepository userRepository, BCryptPasswordEncoder passwordEncoder) {
        this.properties = properties;
        this.menuRepository = menuRepository;
        this.roleRepository = roleRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    /** 校验安全配置并初始化管理员、角色和基础权限。 */
    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        validateSecrets();
        List<Menu> menus = List.of(
            menu("AI 对话", "ai:chat:invoke", 10),
            menu("用户管理", "system:user:manage", 20),
            menu("角色管理", "system:role:manage", 30),
            menu("菜单管理", "system:menu:manage", 40),
            menu("任务日志", "system:task:view", 50)
        );
        Role adminRole = roleRepository.findByCode("ADMIN").orElseGet(() -> {
            Role role = new Role();
            role.setCode("ADMIN");
            role.setName("系统管理员");
            return role;
        });
        adminRole.setMenus(new LinkedHashSet<>(menus));
        adminRole.setEnabled(true);
        roleRepository.save(adminRole);
        String username = properties.getSeed().getAdminUsername();
        if (!userRepository.existsByUsername(username)) {
            UserAccount admin = new UserAccount();
            admin.setUsername(username);
            admin.setDisplayName("系统管理员");
            admin.setPasswordHash(passwordEncoder.encode(properties.getSeed().getAdminPassword()));
            admin.setRoles(new LinkedHashSet<>(List.of(adminRole)));
            userRepository.save(admin);
        }
    }

    /** 获取或创建固定权限菜单。 */
    private Menu menu(String name, String permission, int sortOrder) {
        Menu menu = menuRepository.findByPermission(permission).orElseGet(Menu::new);
        menu.setName(name);
        menu.setPermission(permission);
        menu.setSortOrder(sortOrder);
        menu.setEnabled(true);
        return menuRepository.save(menu);
    }

    /** 阻止弱密钥或示例密码进入运行环境。 */
    private void validateSecrets() {
        String tokenSecret = properties.getToken().getSecret();
        String internalToken = properties.getPythonWorker().getInternalToken();
        String adminPassword = properties.getSeed().getAdminPassword();
        if (tokenSecret == null || tokenSecret.length() < 32 || tokenSecret.contains("replace-with")) {
            throw new IllegalStateException("APP_TOKEN_SECRET 必须设置为至少 32 位随机字符串");
        }
        if (internalToken == null || internalToken.length() < 24 || internalToken.contains("replace-with")) {
            throw new IllegalStateException("PYTHON_WORKER_INTERNAL_TOKEN 必须设置为随机字符串");
        }
        if (adminPassword == null || adminPassword.length() < 10 || adminPassword.contains("replace")) {
            throw new IllegalStateException("APP_SEED_ADMIN_PASSWORD 必须设置为安全密码");
        }
    }
}
