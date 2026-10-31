package org.example.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 旧数据归属迁移（决策 D1：现有库里 user_id=0 的数据归**首个管理员**）。
 *
 * <p>为什么单独一个类而不是塞进 SchemaMigrator：职责不同 ——
 * SchemaMigrator 只做"结构"（加列/加索引，与数据无关，永远安全）；
 * 本类做"数据归属"，有副作用，必须**幂等**且**只在存在管理员时才执行**。</p>
 *
 * <p>幂等性：只更新 `user_id = 0` 的行，执行过一次后这些行都带上了真实 id，再次执行不会重复处理。
 * 若还没有任何 ADMIN（全新部署），直接跳过，等第一个用户注册（自动成为 ADMIN）后再迁。</p>
 */
@Component
@Order(2) // 在 SchemaMigrator（@Order(1)）之后
public class LegacyDataMigration implements CommandLineRunner {

    private static final Logger logger = LoggerFactory.getLogger(LegacyDataMigration.class);

    /** 需要继承旧数据归属的表（不含 users 本身） */
    private static final List<String> TABLES = List.of(
            "study_plan", "plan_task", "daily_checkin", "weak_area", "plan_chapter",
            "chat_session", "knowledge_files", "learner_profile");

    private final JdbcTemplate jdbc;

    public LegacyDataMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(String... args) {
        if (jdbc == null) {
            return;
        }
        try {
            List<Long> admins = jdbc.queryForList(
                    "SELECT id FROM users WHERE role='ADMIN' ORDER BY id ASC LIMIT 1", Long.class);
            if (admins.isEmpty()) {
                logger.info("旧数据迁移：暂无管理员，跳过（首个注册用户成为管理员后会自动迁移）");
                return;
            }
            long adminId = admins.get(0);
            int total = 0;
            for (String table : TABLES) {
                try {
                    // SQLite 的 pragma_table_info 不支持参数绑定表名，此处 table 来自上面的白名单常量
                    Integer cols = jdbc.queryForObject(
                            "SELECT COUNT(*) FROM pragma_table_info('" + table + "') WHERE name='user_id'",
                            Integer.class);
                    if (cols == null || cols == 0) {
                        continue;
                    }
                    int moved = jdbc.update(
                            "UPDATE " + table + " SET user_id=? WHERE user_id=0", adminId);
                    if (moved > 0) {
                        logger.info("旧数据迁移：{}.user_id 0→{}，{} 行", table, adminId, moved);
                        total += moved;
                    }
                } catch (Exception e) {
                    logger.warn("旧数据迁移失败 {}: {}", table, e.getMessage());
                }
            }
            if (total > 0) {
                logger.info("旧数据迁移完成：共 {} 行归属管理员 id={}", total, adminId);
            }
        } catch (Exception e) {
            logger.warn("旧数据迁移跳过：{}", e.getMessage());
        }
    }
}
