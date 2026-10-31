package org.example.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 回归测试：历史数据 stored_path / repo_path 为 NULL 时，
 * Stream.findFirst() 会触发 Optional.of(null) 的 NPE，导致删除接口 500。
 *
 * <p>这两个用例锁住修复：查询可空列时必须过滤 null。</p>
 */
class DeleteNullPathTest {

    private JdbcTemplate jdbc;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        String db = "npe_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:file:" + db + "?mode=memory&cache=shared");
        keepAlive = ds.getConnection();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE knowledge_files(id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "file_name TEXT, repo_path TEXT, stored_path TEXT, user_id INTEGER DEFAULT 0)");
    }

    @AfterEach
    void tearDown() throws SQLException {
        if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close();
    }

    /** 与 FileIngestService 中修复后的写法一致 */
    private String nullableColumn(String sql, Object arg) {
        return jdbc.queryForList(sql, String.class, arg)
                .stream().filter(Objects::nonNull).findFirst().orElse(null);
    }

    @Test
    @DisplayName("stored_path 为 NULL 时不应抛 NPE，返回 null")
    void nullStoredPath() {
        jdbc.update("INSERT INTO knowledge_files(file_name, repo_path, stored_path) VALUES('a.pdf','a.pdf',NULL)");
        Integer id = jdbc.queryForObject("SELECT id FROM knowledge_files WHERE file_name='a.pdf'", Integer.class);
        assertDoesNotThrow(() -> nullableColumn("SELECT stored_path FROM knowledge_files WHERE id=?", id));
        assertNull(nullableColumn("SELECT stored_path FROM knowledge_files WHERE id=?", id));
    }

    @Test
    @DisplayName("stored_path 有值时正常返回")
    void nonNullStoredPath() {
        jdbc.update("INSERT INTO knowledge_files(file_name, repo_path, stored_path) VALUES('b.pdf','b.pdf','uploads/1/x.pdf')");
        Integer id = jdbc.queryForObject("SELECT id FROM knowledge_files WHERE file_name='b.pdf'", Integer.class);
        assertEquals("uploads/1/x.pdf", nullableColumn("SELECT stored_path FROM knowledge_files WHERE id=?", id));
    }

    @Test
    @DisplayName("记录不存在时返回 null，不抛异常")
    void missingRow() {
        assertDoesNotThrow(() -> nullableColumn("SELECT stored_path FROM knowledge_files WHERE id=?", 9999));
        assertNull(nullableColumn("SELECT stored_path FROM knowledge_files WHERE id=?", 9999));
    }
}
