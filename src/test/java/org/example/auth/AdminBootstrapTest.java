package org.example.auth;

import org.example.config.AdminBootstrap;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 引导管理员单测：只提升指定且已存在的邮箱，幂等，不会创建用户。 */
class AdminBootstrapTest {

    private JdbcTemplate jdbc;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        String db = "bs_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:file:" + db + "?mode=memory&cache=shared");
        keepAlive = ds.getConnection();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT,email TEXT,role TEXT,updated_at TEXT)");
        jdbc.update("INSERT INTO users(email,role,updated_at) VALUES('boss@qq.com','USER','t')");
        jdbc.update("INSERT INTO users(email,role,updated_at) VALUES('other@qq.com','USER','t')");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close();
    }

    private AdminBootstrap bootstrap(String email) {
        AdminBootstrap b = new AdminBootstrap(jdbc);
        try {
            var f = AdminBootstrap.class.getDeclaredField("bootstrapEmail");
            f.setAccessible(true);
            f.set(b, email);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return b;
    }

    private String roleOf(String email) {
        return jdbc.queryForObject("SELECT role FROM users WHERE email=?", String.class, email);
    }

    @Test
    @DisplayName("只提升配置里的邮箱，其他用户不受影响")
    void promotesOnlyConfigured() {
        bootstrap("boss@qq.com").run();
        assertEquals("ADMIN", roleOf("boss@qq.com"));
        assertEquals("USER", roleOf("other@qq.com"));
    }

    @Test
    @DisplayName("幂等：重复执行不报错，仍是 ADMIN")
    void idempotent() {
        bootstrap("boss@qq.com").run();
        bootstrap("boss@qq.com").run();
        assertEquals("ADMIN", roleOf("boss@qq.com"));
    }

    @Test
    @DisplayName("未注册的邮箱不会被凭空创建")
    void doesNotCreateUsers() {
        bootstrap("nobody@qq.com").run();
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email='nobody@qq.com'", Integer.class);
        assertEquals(0, n, "引导只提升已存在账号，不创建用户");
    }

    @Test
    @DisplayName("未配置时完全不改动数据库")
    void blankConfigDoesNothing() {
        bootstrap("").run();
        assertEquals("USER", roleOf("boss@qq.com"));
    }

    @Test
    @DisplayName("支持逗号分隔多个邮箱，大小写不敏感")
    void multipleEmails() {
        bootstrap(" BOSS@qq.com , other@qq.com ").run();
        assertEquals("ADMIN", roleOf("boss@qq.com"));
        assertEquals("ADMIN", roleOf("other@qq.com"));
    }
}
