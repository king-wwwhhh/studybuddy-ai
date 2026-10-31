package org.example.service;

import org.example.auth.AuthContext;
import org.example.auth.OwnerSupport;
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
 * 判重作用域单测：解决"A 传过同一文件后，B 上传被误判为已存在"的缺陷。
 *
 * <p>这里直接验证 SQL 作用域语义（与 FileIngestService.alreadyIngested 使用同一条件），
 * 不依赖 Spring 上下文。</p>
 */
class IngestDedupScopeTest {

    private JdbcTemplate jdbc;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        String db = "dedup_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:file:" + db + "?mode=memory&cache=shared");
        keepAlive = ds.getConnection();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE knowledge_files(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "file_name TEXT,user_id INTEGER,file_sha TEXT,chunks INTEGER,status TEXT)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        AuthContext.clear();
        if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close();
    }

    private boolean ingestedInCurrentScope(String sha) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM knowledge_files WHERE file_sha=? AND " + OwnerSupport.visibleScope("user_id"),
                Integer.class, sha);
        return n != null && n > 0;
    }

    @Test
    @DisplayName("A 上传过某内容后，B 上传相同内容不应被判为已存在")
    void sameShaDifferentUsers() {
        String sha = "abc123";
        jdbc.update("INSERT INTO knowledge_files(file_name,user_id,file_sha,chunks,status) VALUES('a.pdf',1,?,2,'READY')", sha);

        AuthContext.set(new AuthContext.Principal(1L, "a@qq.com", "USER"));
        assertTrue(ingestedInCurrentScope(sha), "A 自己应判重命中");

        AuthContext.set(new AuthContext.Principal(2L, "b@qq.com", "USER"));
        assertFalse(ingestedInCurrentScope(sha), "B 不应因 A 的文件被判重");
    }

    @Test
    @DisplayName("同一用户重复上传相同内容仍判重命中")
    void sameUserSameSha() {
        String sha = "def456";
        jdbc.update("INSERT INTO knowledge_files(file_name,user_id,file_sha,chunks,status) VALUES('b.pdf',3,?,1,'READY')", sha);
        AuthContext.set(new AuthContext.Principal(3L, "c@qq.com", "USER"));
        assertTrue(ingestedInCurrentScope(sha));
    }

    @Test
    @DisplayName("系统归属(0)的数据对所有用户判重命中（旧数据继承）")
    void systemDataCounts() {
        String sha = "sys789";
        jdbc.update("INSERT INTO knowledge_files(file_name,user_id,file_sha,chunks,status) VALUES('old.pdf',0,?,1,'READY')", sha);
        AuthContext.set(new AuthContext.Principal(9L, "d@qq.com", "USER"));
        assertTrue(ingestedInCurrentScope(sha), "系统公共数据应对所有人可见/判重");
    }

    @Test
    @DisplayName("不同内容互不干扰")
    void differentSha() {
        jdbc.update("INSERT INTO knowledge_files(file_name,user_id,file_sha,chunks,status) VALUES('x.pdf',1,'shaA',1,'READY')");
        AuthContext.set(new AuthContext.Principal(1L, "a@qq.com", "USER"));
        assertFalse(ingestedInCurrentScope("shaB"));
    }
}
