package org.example.service;

import org.example.auth.AuthContext;
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

/** 会话隔离集成测试（R4）：A 的会话不出现在 B 的列表里，B 也拿不到 A 的会话归属。 */
class MemoryIsolationTest {

    private JdbcTemplate jdbc;
    private MemoryService service;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        String db = "mem_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:file:" + db + "?mode=memory&cache=shared");
        keepAlive = ds.getConnection();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE chat_session(id TEXT PRIMARY KEY,user_id INTEGER DEFAULT 0,agent_name TEXT,"
                + "title TEXT,created_at TEXT,updated_at TEXT)");
        jdbc.execute("CREATE TABLE chat_message(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT,"
                + "role TEXT,content TEXT,created_at TEXT)");
        service = new MemoryService(jdbc, null);
    }

    @AfterEach
    void tearDown() throws SQLException {
        AuthContext.clear();
        if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close();
    }

    private static void login(long id) {
        AuthContext.set(new AuthContext.Principal(id, "u" + id + "@qq.com", "USER"));
    }

    @Test
    @DisplayName("A 的会话不出现在 B 的列表；B 也无法通过 ownsSession 认领")
    void sessionNotVisibleToOthers() {
        login(1);
        service.ensureSession("s-a", "chat", "A 的会话");
        assertEquals(1, service.listSessions().size());
        assertTrue(service.ownsSession("s-a"));

        login(2);
        assertTrue(service.listSessions().isEmpty(), "B 不应看到 A 的会话");
        assertFalse(service.ownsSession("s-a"), "B 不能认领 A 的会话");
    }

    @Test
    @DisplayName("A 写消息后，B 虽然知道 sessionId 也不能访问历史（按归属拦截）")
    void messagesGuardedByOwnership() {
        login(1);
        service.ensureSession("s1", "chat", "会话1");
        service.saveMessage("s1", "user", "内网模型的约束");

        assertFalse(service.ownsSession("s1") == false, "A 自己应能访问");
        login(2);
        assertFalse(service.ownsSession("s1"));
    }

    @Test
    @DisplayName("系统会话（user_id=0）对所有用户可见")
    void legacySessionVisible() {
        jdbc.update("INSERT INTO chat_session(id,user_id,agent_name,title,created_at,updated_at) "
                + "VALUES('legacy',0,'chat','旧会话','2026-01-01','2026-01-01')");
        login(5);
        assertEquals(1, service.listSessions().size());
        assertTrue(service.ownsSession("legacy"));
    }

    @Test
    @DisplayName("ensureSession 幂等：重复调用不报错且不改变归属")
    void ensureIdempotent() {
        login(3);
        service.ensureSession("s3", "chat", "t");
        service.ensureSession("s3", "chat", "t");
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM chat_session WHERE id='s3'", Integer.class);
        assertEquals(1, n);
        assertEquals(3L, jdbc.queryForObject("SELECT user_id FROM chat_session WHERE id='s3'", Long.class));
    }
}
