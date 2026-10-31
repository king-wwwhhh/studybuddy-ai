package org.example.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 管理员统计单测（R6）：用共享内存 SQLite 造数，断言聚合口径与趋势长度。 */
class AdminStatsServiceTest {

    private JdbcTemplate jdbc;
    private AdminStatsService stats;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        String db = "adm_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:file:" + db + "?mode=memory&cache=shared");
        keepAlive = ds.getConnection();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE users(id INTEGER PRIMARY KEY AUTOINCREMENT,email TEXT,nickname TEXT,"
                + "role TEXT,status TEXT,created_at TEXT,last_login_at TEXT)");
        jdbc.execute("CREATE TABLE chat_session(id TEXT PRIMARY KEY,user_id INTEGER,created_at TEXT)");
        jdbc.execute("CREATE TABLE chat_message(id INTEGER PRIMARY KEY AUTOINCREMENT,session_id TEXT,"
                + "role TEXT,content TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE knowledge_files(id INTEGER PRIMARY KEY AUTOINCREMENT,file_name TEXT,"
                + "user_id INTEGER,chunks INTEGER,status TEXT,created_at TEXT)");
        jdbc.execute("CREATE TABLE study_plan(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER,created_at TEXT)");
        jdbc.execute("CREATE TABLE daily_checkin(id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER,created_at TEXT)");
        stats = new AdminStatsService(jdbc);
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close();
    }

    private void seed() {
        String today = LocalDate.now().toString();
        String now = LocalDateTime.now().toString();
        jdbc.update("INSERT INTO users(email,role,status,created_at,last_login_at) VALUES('a@qq.com','ADMIN','ACTIVE',?,?)", now, now);
        jdbc.update("INSERT INTO users(email,role,status,created_at,last_login_at) VALUES('b@qq.com','USER','ACTIVE',?,?)", now, now);
        jdbc.update("INSERT INTO users(email,role,status,created_at,last_login_at) VALUES('c@qq.com','USER','DISABLED',?,?)", now, now);
        jdbc.update("INSERT INTO chat_session(id,user_id,created_at) VALUES('s1',1,?)", now);
        jdbc.update("INSERT INTO chat_session(id,user_id,created_at) VALUES('s2',2,?)", now);
        jdbc.update("INSERT INTO chat_message(session_id,role,content,created_at) VALUES('s1','user','hi',?)", now);
        jdbc.update("INSERT INTO chat_message(session_id,role,content,created_at) VALUES('s1','assistant','yo',?)", now);
        jdbc.update("INSERT INTO knowledge_files(file_name,user_id,chunks,status,created_at) VALUES('a.pdf',1,12,'READY',?)", now);
        jdbc.update("INSERT INTO knowledge_files(file_name,user_id,chunks,status,created_at) VALUES('b.pdf',2,8,'PARTIAL',?)", now);
        jdbc.update("INSERT INTO study_plan(user_id,created_at) VALUES(1,?)", now);
        jdbc.update("INSERT INTO daily_checkin(user_id,created_at) VALUES(1,?)", today);
    }

    @Test
    @DisplayName("overview：各项聚合数字与造数一致")
    void overviewNumbers() {
        seed();
        Map<String, Object> o = stats.overview();
        assertEquals(3, o.get("users"));
        assertEquals(3, o.get("activeUsers7d"));
        assertEquals(2, o.get("sessions"));
        assertEquals(2, o.get("messages"));
        assertEquals(2, o.get("knowledgeFiles"));
        assertEquals(20, o.get("knowledgeChunks"), "12 + 8");
        assertEquals(1, o.get("plans"));
        assertEquals(1, o.get("checkins"));
        assertEquals(2, o.get("messagesToday"));
    }

    @Test
    @DisplayName("trend：返回 N 天且按日期升序，最后一天是今天")
    void trendShape() {
        seed();
        List<Map<String, Object>> t = stats.trend(7);
        assertEquals(7, t.size());
        assertEquals(LocalDate.now().toString(), t.get(6).get("date"), "最后一天应为今天");
        assertEquals(2, t.get(6).get("messages"), "今天有 2 条消息");
        assertEquals(3, t.get(6).get("newUsers"));
    }

    @Test
    @DisplayName("trend：参数越界被夹到 [1,90]")
    void trendClamp() {
        assertEquals(1, stats.trend(0).size());
        assertEquals(90, stats.trend(1000).size());
    }

    @Test
    @DisplayName("knowledge-status：按状态分组计数")
    void knowledgeDistribution() {
        seed();
        List<Map<String, Object>> list = stats.knowledgeStatusDistribution();
        assertEquals(2, list.size());
        Map<String, Object> first = list.get(0);
        assertTrue(first.containsKey("status") && first.containsKey("count"));
    }

    @Test
    @DisplayName("listUsers：不返回密码字段（隐私约定 D4）")
    void listUsersNoSecrets() {
        seed();
        List<Map<String, Object>> list = stats.listUsers(10);
        assertEquals(3, list.size());
        assertFalse(list.get(0).containsKey("passwordHash"));
        assertFalse(list.get(0).containsKey("password_hash"));
    }

    @Test
    @DisplayName("empty 库：所有统计返回 0 而不抛异常")
    void emptyDatabase() {
        Map<String, Object> o = stats.overview();
        assertEquals(0, o.get("users"));
        assertEquals(0, o.get("messages"));
        assertEquals(7, stats.trend(7).size());
    }
}
