package org.example.controller;

import org.example.auth.OwnerSupport;
import org.example.auth.UserRepository;
import org.example.dto.ApiResponse;
import org.example.entity.User;
import org.example.service.AdminStatsService;
import org.example.service.SystemSettingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 管理员接口（{@code /api/admin/**} 由 {@code AuthInterceptor} 强制 role=ADMIN）。
 *
 * <p>隐私约定（决策 D4）：只提供**聚合统计**与**账号维度管理**，
 * 不提供"查看任意用户的对话内容"——避免论文中出现隐私争议。</p>
 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {
    private static final Logger logger = LoggerFactory.getLogger(AdminController.class);

    @Autowired
    private JdbcTemplate jdbc;

    /** 与 AuthService 一致的 BCrypt 配置（cost 10） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder(10);

    @Autowired
    private SystemSettingService systemSettingService;

    private static String trim(String s) {
        return s == null ? "" : s.trim();
    }
    private final AdminStatsService stats;
    private final UserRepository users;
    public AdminController(AdminStatsService stats, UserRepository users) {
        this.stats = stats;
        this.users = users;
    }

    /** 概览：用户数、会话数、消息数、知识库分片数等 */
    @GetMapping("/stats")
    public ResponseEntity<?> overview() {
        return ok(stats.overview());
    }
    /** 近 N 日趋势（默认 7 天） */
    @GetMapping("/trend")
    public ResponseEntity<?> trend(@RequestParam(defaultValue = "7") int days) {
        return ok(stats.trend(days));
    }
    /** 知识库文件状态分布（饼图） */
    @GetMapping("/knowledge-status")
    public ResponseEntity<?> knowledgeStatus() {
        return ok(stats.knowledgeStatusDistribution());
    }
    /** 用户列表（不含密码与其他用户对话内容） */
    @GetMapping("/users")
    public ResponseEntity<?> listUsers(@RequestParam(defaultValue = "50") int limit) {
        List<Map<String, Object>> list = stats.listUsers(limit);
        return ok(list);
    }
    /** 启用/禁用账号 */
    @PostMapping("/users/{id}/status")
    public ResponseEntity<?> updateStatus(@PathVariable long id, @RequestParam String status) {
        String s = "DISABLED".equalsIgnoreCase(status) ? "DISABLED" : "ACTIVE";
        User target = users.findById(id).orElse(null);
        if (target == null) {
            return fail(404, "用户不存在");
        }
        users.updateStatus(id, s);
        logger.info("管理员调整用户状态: id={} -> {}", id, s);
        return ok(Map.of("id", id, "status", s));
    }
    /** 提升为管理员（按邮箱） */
    @PostMapping("/users/promote")
    public ResponseEntity<?> promote(@RequestParam String email) {
        int n = users.promoteToAdmin(email);
        if (n == 0) {
            return fail(404, "用户不存在: " + email);
        }
        logger.info("管理员提升: email={}", email);
        return ok(Map.of("email", email, "role", User.ROLE_ADMIN));
    }
    private static ResponseEntity<Map<String, Object>> ok(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return ResponseEntity.ok(body);
    }
    private static ResponseEntity<Map<String, Object>> fail(int status, String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", false);
        body.put("code", status);
        body.put("message", message);
        return ResponseEntity.status(status).body(body);
    }
// ==================== 用户管理（增删改查） ====================

    /** 新增账号（管理员创建，可指定角色），不经过邮箱验证 */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> body) {
        try {
            String email = trim(body.get("email"));
            String password = trim(body.get("password"));
            String username = trim(body.get("username"));
            String role = trim(body.get("role"));
            if (email.isEmpty() || !email.contains("@")) {
                return ResponseEntity.ok(ApiResponse.error("邮箱格式不正确"));
            }
            if (password.length() < 8) {
                return ResponseEntity.ok(ApiResponse.error("密码至少 8 位"));
            }
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE email=?", Integer.class, email);
            if (exists != null && exists > 0) {
                return ResponseEntity.ok(ApiResponse.error("该邮箱已存在"));
            }
            // 用户名：必填、唯一、不能违规
            String nameError = org.example.auth.UsernamePolicy.validate(username);
            if (nameError != null) {
                return ResponseEntity.ok(ApiResponse.error(nameError));
            }
            Integer nameTaken = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM users WHERE LOWER(username)=LOWER(?)", Integer.class, username);
            if (nameTaken != null && nameTaken > 0) {
                return ResponseEntity.ok(ApiResponse.error("该用户名已被占用"));
            }
            String finalRole = "ADMIN".equalsIgnoreCase(role) ? "ADMIN" : "USER";
            String now = LocalDateTime.now().toString();
            jdbc.update("INSERT INTO users(email, username, password_hash, nickname, role, status, created_at, updated_at) " +
                            "VALUES(?,?,?,?,?, 'ACTIVE', ?, ?)",
                    email, username, passwordEncoder.encode(password), username, finalRole, now, now);
            logger.info("管理员创建账号: {} role={}", email, finalRole);
            return ResponseEntity.ok(ApiResponse.success(true));
        } catch (Exception e) {
            logger.error("创建账号失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /** 修改用户（昵称 / 角色） */
    @PostMapping("/users/{id}")
    public ResponseEntity<?> updateUser(@PathVariable long id, @RequestBody Map<String, String> body) {
        try {
            Integer exists = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE id=?", Integer.class, id);
            if (exists == null || exists == 0) {
                return ResponseEntity.ok(ApiResponse.error("用户不存在"));
            }
            String now = LocalDateTime.now().toString();
            // 改用户名：管理员和个人都能改，但必须合规且不重复
            String username = body.get("username");
            if (username != null && !username.trim().isEmpty()) {
                String name = username.trim();
                String nameError = org.example.auth.UsernamePolicy.validate(name);
                if (nameError != null) {
                    return ResponseEntity.ok(ApiResponse.error(nameError));
                }
                Integer taken = jdbc.queryForObject(
                        "SELECT COUNT(*) FROM users WHERE LOWER(username)=LOWER(?) AND id<>?", Integer.class, name, id);
                if (taken != null && taken > 0) {
                    return ResponseEntity.ok(ApiResponse.error("该用户名已被占用"));
                }
                jdbc.update("UPDATE users SET username=?, nickname=?, updated_at=? WHERE id=?", name, name, now, id);
            }
            String newRole = body.get("role");
            if (newRole != null && !newRole.trim().isEmpty()) {
                String r = "ADMIN".equalsIgnoreCase(newRole.trim()) ? "ADMIN" : "USER";
                if ("USER".equals(r)) {
                    Integer admins = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'", Integer.class);
                    String curRole = jdbc.queryForObject("SELECT role FROM users WHERE id=?", String.class, id);
                    if ("ADMIN".equalsIgnoreCase(curRole) && admins != null && admins <= 1) {
                        return ResponseEntity.ok(ApiResponse.error("这是最后一个管理员，不能降级"));
                    }
                }
                jdbc.update("UPDATE users SET role=?, updated_at=? WHERE id=?", r, now, id);
            }
            return ResponseEntity.ok(ApiResponse.success(true));
        } catch (Exception e) {
            logger.error("修改用户失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /** 重置密码 */
    @PostMapping("/users/{id}/password")
    public ResponseEntity<?> resetPassword(@PathVariable long id, @RequestBody Map<String, String> body) {
        try {
            String password = trim(body.get("password"));
            if (password.length() < 8) {
                return ResponseEntity.ok(ApiResponse.error("密码至少 8 位"));
            }
            int n = jdbc.update("UPDATE users SET password_hash=?, updated_at=? WHERE id=?",
                    passwordEncoder.encode(password), LocalDateTime.now().toString(), id);
            return ResponseEntity.ok(n > 0 ? ApiResponse.success(true) : ApiResponse.error("用户不存在"));
        } catch (Exception e) {
            logger.error("重置密码失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /** 删除用户及其业务数据 */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable long id) {
        try {
            Long me = OwnerSupport.currentUserId();
            if (me != null && me == id) {
                return ResponseEntity.ok(ApiResponse.error("不能删除当前登录的账号"));
            }
            String targetRole = jdbc.query("SELECT role FROM users WHERE id=?",
                    (rs, rowNum) -> rs.getString("role"), id).stream().findFirst().orElse(null);
            if (targetRole == null) {
                return ResponseEntity.ok(ApiResponse.error("用户不存在"));
            }
            if ("ADMIN".equalsIgnoreCase(targetRole)) {
                Integer admins = jdbc.queryForObject("SELECT COUNT(*) FROM users WHERE role='ADMIN' AND status='ACTIVE'", Integer.class);
                if (admins != null && admins <= 1) {
                    return ResponseEntity.ok(ApiResponse.error("这是最后一个管理员，不能删除"));
                }
            }
            String[] tables = {"knowledge_files", "chat_message", "chat_session", "learner_profile",
                    "plan_task", "daily_checkin", "weak_area", "plan_chapter", "study_plan"};
            for (String table : tables) {
                try {
                    jdbc.update("DELETE FROM " + table + " WHERE user_id=?", id);
                } catch (Exception ignore) {
                    // 该表可能没有 user_id 列，跳过
                }
            }
            jdbc.update("DELETE FROM users WHERE id=?", id);
            logger.info("管理员删除用户: id={}", id);
            return ResponseEntity.ok(ApiResponse.success(true));
        } catch (Exception e) {
            logger.error("删除用户失败", e);
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    // ==================== 系统开关 ====================

    /** 读取系统开关 */
    @GetMapping("/settings")
    public ResponseEntity<?> settings() {
        try {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("registrationOpen", systemSettingService.isRegistrationOpen());
            out.put("all", systemSettingService.all());
            return ResponseEntity.ok(ApiResponse.success(out));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }

    /** 修改系统开关（如停止注册） */
    @PostMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody Map<String, Object> body) {
        try {
            if (body.containsKey("registrationOpen")) {
                boolean open = Boolean.parseBoolean(String.valueOf(body.get("registrationOpen")));
                systemSettingService.set(SystemSettingService.KEY_REGISTRATION_OPEN, String.valueOf(open));
            }
            return ResponseEntity.ok(ApiResponse.success(systemSettingService.all()));
        } catch (Exception e) {
            return ResponseEntity.ok(ApiResponse.error(e.getMessage()));
        }
    }
}
