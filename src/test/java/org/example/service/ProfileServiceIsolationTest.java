package org.example.service;

import org.example.auth.AuthContext;
import org.example.entity.LearnerProfile;
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

/**
 * 画像多用户隔离集成测试（R2）：用**共享内存 SQLite** 真跑 SQL，不起 Spring 上下文。
 *
 * <p>踩坑记录：`jdbc:sqlite::memory:` 是"每个连接一个独立库"，JdbcTemplate 每次取新连接就看不到表。
 * 解法是用 `file:<name>?mode=memory&cache=shared` + 持有一个保活连接，
 * 这样同一进程内多个连接访问的是同一个内存库。</p>
 *
 * <p>这组测试是"数据隔离"最关键的证据：A 存的画像，B 绝不能读到。</p>
 */
class ProfileServiceIsolationTest {

    private JdbcTemplate jdbc;
    private ProfileService service;
    /** 保活连接：没有它，内存库会随第一个连接关闭而消失 */
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        String db = "iso_" + UUID.randomUUID().toString().replace("-", "");
        String url = "jdbc:sqlite:file:" + db + "?mode=memory&cache=shared";
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl(url);
        keepAlive = ds.getConnection();

        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE learner_profile ("
                + "id INTEGER PRIMARY KEY AUTOINCREMENT,user_id INTEGER DEFAULT 0,name TEXT,subjects TEXT,"
                + "exam_date TEXT,learning_style TEXT,goals TEXT,weak_subjects TEXT,"
                + "created_at TEXT NOT NULL,updated_at TEXT NOT NULL)");
        jdbc.execute("CREATE UNIQUE INDEX uq_learner_profile_user ON learner_profile(user_id)");
        service = new ProfileService(jdbc);
    }

    @AfterEach
    void tearDown() throws SQLException {
        AuthContext.clear();
        if (keepAlive != null && !keepAlive.isClosed()) {
            keepAlive.close();
        }
    }

    private static void login(long userId) {
        AuthContext.set(new AuthContext.Principal(userId, "u" + userId + "@qq.com", "USER"));
    }

    @Test
    @DisplayName("A 存画像后，B 读到的是自己的默认画像，而不是 A 的")
    void isolationBetweenUsers() {
        login(1);
        LearnerProfile a = new LearnerProfile();
        a.setName("A 同学");
        a.setSubjects("数学,英语");
        service.save(a);

        login(2);
        LearnerProfile b = service.get();
        assertEquals("同学", b.getName(), "B 应拿到默认画像，不能看到 A 的");
        assertNull(b.getSubjects(), "B 的科目应为空");

        login(1);
        assertEquals("A 同学", service.get().getName(), "A 的画像必须原样保留");
        assertEquals("数学,英语", service.get().getSubjects());
    }

    @Test
    @DisplayName("同一用户重复保存是更新而非新增（user_id 唯一）")
    void saveIsUpsert() {
        login(5);
        LearnerProfile p = new LearnerProfile();
        p.setName("第一次");
        service.save(p);
        p.setName("第二次");
        service.save(p);

        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_profile WHERE user_id=5", Integer.class);
        assertEquals(1, rows, "同一用户只能有一行画像");
        assertEquals("第二次", service.get().getName());
    }

    @Test
    @DisplayName("首次读取自动创建默认画像")
    void autoCreateOnFirstRead() {
        login(3);
        LearnerProfile p = service.get();
        assertNotNull(p);
        assertEquals("同学", p.getName());
        Integer rows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_profile WHERE user_id=3", Integer.class);
        assertEquals(1, rows);
    }

    @Test
    @DisplayName("未登录（SYSTEM=0）也有独立画像，不与他人混淆")
    void systemOwnerHasOwnRow() {
        AuthContext.clear();
        LearnerProfile sys = new LearnerProfile();
        sys.setName("系统默认");
        service.save(sys);

        login(9);
        assertEquals("同学", service.get().getName(), "真实用户不能读到 SYSTEM 的画像");

        AuthContext.clear();
        assertEquals("系统默认", service.get().getName());
    }
}
