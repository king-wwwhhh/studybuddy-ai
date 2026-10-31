package org.example.controller;

import org.example.auth.UserRepository;
import org.example.entity.User;
import org.example.service.AdminStatsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
}
