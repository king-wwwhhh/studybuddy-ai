package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 引导管理员（bootstrap）：把配置里指定的邮箱提升为 ADMIN。
 *
 * <p>为什么需要它：取消「首个注册者自动成管理员」后，全新部署没有任何管理员，
 * 后台的「设为管理员」按钮就没有人能点。这里提供一个**显式、可控**的引导入口：</p>
 *
 * <ul>
 *   <li>只提升 `auth.bootstrap-admin-email` 指定的邮箱（逗号分隔可多个）；</li>
 *   <li>**只对已注册账号生效**，不会凭空创建用户；</li>
 *   <li>幂等：已经是 ADMIN 的直接跳过；每次启动都会检查（便于补救）;</li>
 *   <li>未配置则完全跳过，日志提示如何设置。</li>
 * </ul>
 *
 * <p>安全考虑：该配置属于部署者权限，等价于"服务器控制台"级别的信任，不通过 HTTP 暴露。</p>
 */
@Component
@Order(3) // 在 SchemaMigrator(1) / LegacyDataMigration(2) 之后
public class AdminBootstrap implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(AdminBootstrap.class);

    private final JdbcTemplate jdbc;

    @Value("${auth.bootstrap-admin-email:}")
    private String bootstrapEmail;

    public AdminBootstrap(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        if (bootstrapEmail == null || bootstrapEmail.isBlank()) {
            logger.info("引导管理员：未配置 AUTH_BOOTSTRAP_ADMIN_EMAIL，跳过。" +
                    "如需创建管理员，设置该环境变量为已注册邮箱后重启");
            return;
        }
        for (String raw : bootstrapEmail.split(",")) {
            String email = raw.trim().toLowerCase();
            if (email.isEmpty()) {
                continue;
            }
            try {
                List<String> roles = jdbc.queryForList(
                        "SELECT role FROM users WHERE email=?", String.class, email);
                if (roles.isEmpty()) {
                    logger.warn("引导管理员：账号尚未注册，跳过 {}（注册后重启即可提升）", email);
                    continue;
                }
                if ("ADMIN".equalsIgnoreCase(roles.get(0))) {
                    logger.info("引导管理员：{} 已是 ADMIN，跳过", email);
                    continue;
                }
                int n = jdbc.update("UPDATE users SET role='ADMIN', updated_at=? WHERE email=?",
                        LocalDateTime.now().toString(), email);
                if (n > 0) {
                    logger.info("引导管理员：已将 {} 提升为 ADMIN", email);
                }
            } catch (Exception e) {
                logger.warn("引导管理员失败 {}: {}", email, e.getMessage());
            }
        }
    }
}
