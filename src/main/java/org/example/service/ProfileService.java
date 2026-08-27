package org.example.service;

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
 * 单例行 id=1：科目、考试日期、学习风格、目标、历史薄弱科目。Coach/Planner 读取以个性化作答。
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

    public LearnerProfile get() {
        LearnerProfile p = jdbc.query("SELECT * FROM learner_profile WHERE id=1", ROW).stream().findFirst().orElse(null);
        if (p == null) {
            String now = LocalDateTime.now().toString();
            jdbc.update("INSERT INTO learner_profile(id,name,created_at,updated_at) VALUES(1,?,?,?)", "同学", now, now);
            p = new LearnerProfile();
            p.setId(1L);
            p.setName("同学");
        }
        return p;
    }

    public void save(LearnerProfile p) {
        String now = LocalDateTime.now().toString();
        LearnerProfile existing = get();
        if (existing == null || existing.getId() == null) {
            jdbc.update(
                    "INSERT INTO learner_profile(id,name,subjects,exam_date,learning_style,goals,weak_subjects,created_at,updated_at) VALUES(1,?,?,?,?,?,?,?,?)",
                    p.getName(), p.getSubjects(), p.getExamDate(), p.getLearningStyle(), p.getGoals(),
                    p.getWeakSubjects(), now, now);
        } else {
            jdbc.update(
                    "UPDATE learner_profile SET name=?,subjects=?,exam_date=?,learning_style=?,goals=?,weak_subjects=?,updated_at=? WHERE id=1",
                    p.getName(), p.getSubjects(), p.getExamDate(), p.getLearningStyle(), p.getGoals(),
                    p.getWeakSubjects(), now);
        }
        logger.info("更新学习者画像");
    }
}
