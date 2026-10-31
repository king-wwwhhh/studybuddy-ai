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
            new String[] { "plan_task", "chapter", "TEXT" },
            // ===== 多用户隔离（R1）：8 张业务表统一加 user_id，旧行归 0（SYSTEM）=====
            // ===== 原文件落盘（可回溯/可重解析）=====
            new String[] { "knowledge_files", "stored_path", "TEXT" },
            new String[] { "study_plan", "user_id", "INTEGER DEFAULT 0" },
            new String[] { "plan_task", "user_id", "INTEGER DEFAULT 0" },
            new String[] { "daily_checkin", "user_id", "INTEGER DEFAULT 0" },
            new String[] { "weak_area", "user_id", "INTEGER DEFAULT 0" },
            new String[] { "plan_chapter", "user_id", "INTEGER DEFAULT 0" },
            new String[] { "chat_session", "user_id", "INTEGER DEFAULT 0" },
            new String[] { "knowledge_files", "user_id", "INTEGER DEFAULT 0" },
            new String[] { "learner_profile", "user_id", "INTEGER DEFAULT 0" }
    );

    @Override
    public void run(String... args) {
        // 系统开关表（老库补建）
        try {
            jdbc.execute("CREATE TABLE IF NOT EXISTS system_settings (" +
                    "setting_key TEXT PRIMARY KEY, setting_value TEXT, updated_at TEXT)");
            logger.info("系统开关表检查完成");
        } catch (Exception e) {
            logger.warn("创建 system_settings 失败: {}", e.getMessage());
        }
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
            ensureIndexes(tables);
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

    /**
     * 用户维度索引（幂等）：多用户隔离后，几乎所有查询都会带 user_id 条件。
     * 用 IF NOT EXISTS 保证可重复执行。
     */
    private void ensureIndexes(List<String> tables) {
        String[][] indexes = {
                // 表名, 索引名, 列定义
                { "study_plan", "idx_study_plan_user", "user_id" },
                { "plan_task", "idx_plan_task_user", "user_id" },
                { "daily_checkin", "idx_daily_checkin_user", "user_id" },
                { "weak_area", "idx_weak_area_user", "user_id" },
                { "plan_chapter", "idx_plan_chapter_user", "user_id" },
                { "chat_session", "idx_chat_session_user", "user_id" },
                { "chat_message", "idx_chat_message_session", "session_id" },
                { "knowledge_files", "idx_knowledge_files_user", "user_id" },
                { "learner_profile", "idx_learner_profile_user", "user_id" }
        };
        for (String[] ix : indexes) {
            if (!tables.contains(ix[0])) continue;
            try {
                jdbc.execute("CREATE INDEX IF NOT EXISTS " + ix[1] + " ON " + ix[0] + "(" + ix[2] + ")");
            } catch (DataAccessException ex) {
                logger.warn("创建索引失败 {}({}): {}", ix[1], ix[2], ex.getMessage());
            }
        }
        logger.info("用户维度索引检查完成");
    }
}