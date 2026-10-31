package org.example.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.example.auth.AuthContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** 查询扩展：短问题必须被补上文件名，才能匹配到具体资料。 */
class QueryExpansionTest {

    private JdbcTemplate jdbc;
    private Connection keepAlive;

    @BeforeEach
    void setUp() throws SQLException {
        AuthContext.set(new AuthContext.Principal(1L, "test@qq.com", "USER"));
        String db = "qe_" + UUID.randomUUID().toString().replace("-", "");
        DriverManagerDataSource ds = new DriverManagerDataSource();
        ds.setDriverClassName("org.sqlite.JDBC");
        ds.setUrl("jdbc:sqlite:file:" + db + "?mode=memory&cache=shared");
        keepAlive = ds.getConnection();
        jdbc = new JdbcTemplate(ds);
        jdbc.execute("CREATE TABLE knowledge_files(id INTEGER PRIMARY KEY AUTOINCREMENT, file_name TEXT," +
                " user_id INTEGER DEFAULT 0, updated_at TEXT)");
        jdbc.update("INSERT INTO knowledge_files(file_name,user_id,updated_at) VALUES('王浩杰-AI应用与智能体开发.pdf',1,'2026-10-31')");
        jdbc.update("INSERT INTO knowledge_files(file_name,user_id,updated_at) VALUES('光合作用原理.md',1,'2026-10-30')");
    }

    @AfterEach
    void tearDown() throws SQLException {
        AuthContext.clear();
        if (keepAlive != null && !keepAlive.isClosed()) keepAlive.close();
    }

    private QueryExpansionService svc(boolean enabled, int maxFiles) {
        QueryExpansionService s = new QueryExpansionService(jdbc);
        set(s, "enabled", enabled);
        set(s, "maxFiles", maxFiles);
        return s;
    }

    private static void set(Object o, String f, Object v) {
        try { var x = o.getClass().getDeclaredField(f); x.setAccessible(true); x.set(o, v); }
        catch (Exception e) { throw new RuntimeException(e); }
    }

    @Test
    @DisplayName("短问题被补上知识库文件名")
    void expandsWithFileNames() {
        String out = svc(true, 6).expand("求职意向是什么", null);
        assertTrue(out.startsWith("求职意向是什么"), "原问题必须保留在前面");
        assertTrue(out.contains("王浩杰"), "应拼入文件名主体: " + out);
        assertTrue(out.contains("光合作用"), "应拼入另一个文件名: " + out);
    }

    @Test
    @DisplayName("扩展词去掉扩展名，避免 .pdf 噪声")
    void stripsExtension() {
        String out = svc(true, 6).expand("内容", null);
        assertFalse(out.contains(".pdf"), "不应出现扩展名: " + out);
        assertFalse(out.contains(".md"), "不应出现扩展名: " + out);
    }

    @Test
    @DisplayName("拼入上一轮提问，补全指代")
    void includesPrevious() {
        String out = svc(true, 6).expand("这份文件讲了什么", "王浩杰的简历在哪里");
        assertTrue(out.contains("王浩杰的简历在哪里"), "应带入上一轮: " + out);
    }

    @Test
    @DisplayName("关闭开关时原样返回")
    void disabledReturnsOriginal() {
        assertEquals("求职意向是什么", svc(false, 6).expand("求职意向是什么", "上一轮"));
    }

    @Test
    @DisplayName("空问题不处理")
    void blankQuestion() {
        assertEquals("", svc(true, 6).expand("", null));
        assertNull(svc(true, 6).expand(null, null));
    }

    @Test
    @DisplayName("maxFiles 限制拼接数量")
    void respectsMaxFiles() {
        String out = svc(true, 1).expand("问题", null);
        long count = out.contains("王浩杰") ? 1 : 0;
        count += out.contains("光合作用") ? 1 : 0;
        assertTrue(count <= 1, "只应拼入 1 个文件名，实际 " + count + ": " + out);
    }
}
