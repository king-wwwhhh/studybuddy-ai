package org.example.auth;

import org.example.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** 用户表访问（SQLite） */
@Repository
public class UserRepository {

    private static final Logger logger = LoggerFactory.getLogger(UserRepository.class);

    private static final RowMapper<User> ROW = (rs, i) -> {
        User u = new User();
        u.setId(rs.getLong("id"));
        u.setEmail(rs.getString("email"));
        try { u.setUsername(rs.getString("username")); } catch (Exception ignore) { /* 老库可能没这列 */ }
        u.setPasswordHash(rs.getString("password_hash"));
        u.setNickname(rs.getString("nickname"));
        u.setRole(rs.getString("role"));
        u.setStatus(rs.getString("status"));
        u.setCreatedAt(parse(rs.getString("created_at")));
        u.setUpdatedAt(parse(rs.getString("updated_at")));
        u.setLastLoginAt(parse(rs.getString("last_login_at")));
        return u;
    };

    private final JdbcTemplate jdbc;

    public UserRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime parse(String s) {
        try {
            return s == null ? null : LocalDateTime.parse(s);
        } catch (Exception e) {
            return null;
        }
    }

    public Optional<User> findByEmail(String email) {
        List<User> list = jdbc.query("SELECT * FROM users WHERE email=?", ROW, email);
        return list.stream().findFirst();
    }

    /** 按登录名查（大小写不敏感比对） */
    public Optional<User> findByUsername(String username) {
        if (username == null || username.isBlank()) return Optional.empty();
        List<User> list = jdbc.query(
                "SELECT * FROM users WHERE LOWER(username)=LOWER(?)", ROW, username.trim());
        return list.stream().findFirst();
    }

    /** 登录入口：邮箱或用户名任一匹配即可 */
    public Optional<User> findByEmailOrUsername(String account) {
        if (account == null || account.isBlank()) return Optional.empty();
        String a = account.trim();
        if (a.contains("@")) {
            Optional<User> byEmail = findByEmail(a.toLowerCase());
            if (byEmail.isPresent()) return byEmail;
        }
        return findByUsername(a);
    }

    /** 登录名是否已被占用 */
    public boolean existsUsername(String username) {
        if (username == null || username.isBlank()) return false;
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM users WHERE LOWER(username)=LOWER(?)", Integer.class, username.trim());
        return n != null && n > 0;
    }

    public Optional<User> findById(long id) {
        List<User> list = jdbc.query("SELECT * FROM users WHERE id=?", ROW, id);
        return list.stream().findFirst();
    }

    public long insert(String email, String username, String passwordHash, String nickname, String role) {
        String now = LocalDateTime.now().toString();
        KeyHolder keys = new GeneratedKeyHolder();
        jdbc.update(con -> {
            PreparedStatement ps = con.prepareStatement(
                    "INSERT INTO users(email,username,password_hash,nickname,role,status,created_at,updated_at) VALUES(?,?,?,?,?,?,?,?)",
                    Statement.RETURN_GENERATED_KEYS);
            ps.setString(1, email);
            ps.setString(2, username);
            ps.setString(3, passwordHash);
            ps.setString(4, nickname);
            ps.setString(5, role);
            ps.setString(6, "ACTIVE");
            ps.setString(7, now);
            ps.setString(8, now);
            return ps;
        }, keys);
        Number key = keys.getKey();
        return key == null ? -1 : key.longValue();
    }

    public void touchLogin(long id) {
        jdbc.update("UPDATE users SET last_login_at=?, updated_at=? WHERE id=?",
                LocalDateTime.now().toString(), LocalDateTime.now().toString(), id);
    }

    public void updatePassword(long id, String passwordHash) {
        jdbc.update("UPDATE users SET password_hash=?, updated_at=? WHERE id=?",
                passwordHash, LocalDateTime.now().toString(), id);
    }

    public void updateStatus(long id, String status) {
        jdbc.update("UPDATE users SET status=?, updated_at=? WHERE id=?",
                status, LocalDateTime.now().toString(), id);
    }

    public int promoteToAdmin(String email) {
        return jdbc.update("UPDATE users SET role='ADMIN', updated_at=? WHERE email=?",
                LocalDateTime.now().toString(), email);
    }

    public int countAll() {
        Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
        return n == null ? 0 : n;
    }

    public long countActiveSince(LocalDateTime since) {
        Long n = jdbc.queryForObject("SELECT COUNT(DISTINCT id) FROM users WHERE last_login_at >= ?",
                Long.class, since.toString());
        return n == null ? 0 : n;
    }

    public List<User> listAll(int limit) {
        return jdbc.query("SELECT * FROM users ORDER BY id DESC LIMIT ?", ROW, Math.max(1, limit));
    }
}
