package org.example.service;

import org.example.auth.OwnerSupport;
import org.example.entity.LearnerProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;

import java.sql.ResultSet;
import java.time.LocalDateTime;

/**
 * 学习者画像服务（长期个性化记忆）。
 *
 * <p>多用户改造（R2）：原来是一张单例表（`CHECK(id=1)`，读写都硬编码 `id=1`），
 * 第二个用户注册后会**覆盖第一个人的画像**。现在改为**每人一行**（`user_id` 唯一），
 * 用户 id 一律从 {@link OwnerSupport} 取，绝不信任前端传参。</p>
 *
 * <p>兼容策略：首次读取时若本人无画像，自动创建一行（name 默认"同学"）；
 * 旧库里的单例画像（user_id=0）在迁移脚本里归给首个管理员，见 {@code LegacyDataMigration}。</p>
 */
@Service
public class ProfileService {

    private static final Logger logger = LoggerFactory.getLogger(ProfileService.class);
    private final JdbcTemplate jdbc;

    @Autowired
    public ProfileService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static final RowMapper<LearnerProfile> ROW = (ResultSet rs, int i) -> {
        LearnerProfile p = new LearnerProfile();
        p.setId(rs.getLong("id"));
        p.setName(rs.getString("name"));
        p.setSubjects(rs.getString("subjects"));
        p.setExamDate(rs.getString("exam_date"));
        p.setLearningStyle(rs.getString("learning_style"));
        p.setGoals(rs.getString("goals"));
        p.setWeakSubjects(rs.getString("weak_subjects"));
        p.setCreatedAt(rs.getString("created_at") != null ? LocalDateTime.parse(rs.getString("created_at")) : null);
        p.setUpdatedAt(rs.getString("updated_at") != null ? LocalDateTime.parse(rs.getString("updated_at")) : null);
        return p;
    };

    /** 读取当前用户画像；不存在则创建默认行 */
    public LearnerProfile get() {
        return get(OwnerSupport.currentUserId());
    }

    /** 指定用户画像（供管理员/工具显式指定用；正常业务走无参重载） */
    public LearnerProfile get(long userId) {
        LearnerProfile p = jdbc.query("SELECT * FROM learner_profile WHERE user_id=? LIMIT 1", ROW, userId)
                .stream().findFirst().orElse(null);
        if (p == null) {
            String now = LocalDateTime.now().toString();
            jdbc.update("INSERT INTO learner_profile(user_id,name,created_at,updated_at) VALUES(?,?,?,?)",
                    userId, "同学", now, now);
            p = new LearnerProfile();
            p.setName("同学");
            p.setCreatedAt(LocalDateTime.now());
            logger.info("初始化用户画像: userId={}", userId);
        }
        return p;
    }

    /** 保存当前用户画像（存在则更新，不存在则插入） */
    public void save(LearnerProfile p) {
        save(OwnerSupport.currentUserId(), p);
    }

    public void save(long userId, LearnerProfile p) {
        String now = LocalDateTime.now().toString();
        Integer exists = jdbc.queryForObject(
                "SELECT COUNT(*) FROM learner_profile WHERE user_id=?", Integer.class, userId);
        if (exists == null || exists == 0) {
            jdbc.update("INSERT INTO learner_profile(user_id,name,subjects,exam_date,learning_style,goals,weak_subjects,created_at,updated_at) "
                            + "VALUES(?,?,?,?,?,?,?,?,?)",
                    userId, p.getName(), p.getSubjects(), p.getExamDate(), p.getLearningStyle(), p.getGoals(),
                    p.getWeakSubjects(), now, now);
        } else {
            jdbc.update("UPDATE learner_profile SET name=?,subjects=?,exam_date=?,learning_style=?,goals=?,weak_subjects=?,updated_at=? "
                            + "WHERE user_id=?",
                    p.getName(), p.getSubjects(), p.getExamDate(), p.getLearningStyle(), p.getGoals(),
                    p.getWeakSubjects(), now, userId);
        }
        logger.info("更新学习者画像: userId={}", userId);
    }
}
