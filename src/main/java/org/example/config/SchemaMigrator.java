package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 启动期幂等迁移：当数据库表存在但缺列时追加列。
 * <p>SQLite 的 ALTER TABLE ADD COLUMN 在列已存在时抛 duplicate column，
 * 本类一条条 try/ignore 比 schema.sql 写 ALTER 更安全。</p>
 */
@Component
@Order(0)
public class SchemaMigrator implements CommandLineRunner {
    private static final Logger logger = LoggerFactory.getLogger(SchemaMigrator.class);

    @Autowired
    private JdbcTemplate jdbc;

    private final List<String[]> migrations = Arrays.asList(
            // table, column, definition
            new String[] { "study_plan", "priority", "INTEGER DEFAULT 3" },
            new String[] { "study_plan", "hours_per_day", "REAL DEFAULT 2.0" },
            new String[] { "study_plan", "notes", "TEXT" },
            new String[] { "study_plan", "goal_text", "TEXT" },
            new String[] { "study_plan", "last_replan_at", "TEXT" },
            new String[] { "study_plan", "auto_replan_count", "INTEGER DEFAULT 0" },
            new String[] { "plan_task", "chapter", "TEXT" }
    );

    @Override
    public void run(String... args) {
        if (jdbc == null) return;
        try {
            // 只对已存在的表做迁移，避免新建库时 CREATE TABLE 已含列后再 ADD 失败
            List<String> tables = new ArrayList<>();
            try {
                tables = jdbc.queryForList(
                        "SELECT name FROM sqlite_master WHERE type='table'", String.class);
            } catch (DataAccessException ignored) {
                // 库/元数据表不存在，跳过迁移
                return;
            }
            for (String[] m : migrations) {
                String table = m[0], column = m[1], def = m[2];
                if (!tables.contains(table)) continue;
                Integer cols = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM pragma_table_info(?) WHERE name=?",
                        Integer.class, table, column);
                if (cols != null && cols > 0) continue;
                try {
                    jdbc.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + def);
                    logger.info("迁移成功：{}.{} 追加列 {}", table, column, def);
                } catch (DataAccessException ex) {
                    logger.warn("跳过迁移 {}.{} ({})", table, column, ex.getMessage());
                }
            }
        } catch (Exception e) {
            logger.warn("SchemaMigrator 跳过：{}", e.getMessage());
        }
    }
}
