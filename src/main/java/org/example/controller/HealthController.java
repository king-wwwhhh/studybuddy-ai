package org.example.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 系统健康检查（不要求登录，供运维探针/部署平台探活）。
 * 与 /api/milvus/health（检查向量库）不同：这里只看应用是否活着 + 数据库是否可读。
 * 不暴露任何业务数据，因此放在鉴权白名单里。
 */
@RestController
@RequestMapping("/api/health")
public class HealthController {

    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("time", LocalDateTime.now().toString());
        try {
            Integer n = jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class);
            body.put("db", "UP");
            body.put("users", n == null ? 0 : n);
        } catch (Exception e) {
            body.put("status", "DEGRADED");
            body.put("db", "DOWN");
            body.put("dbError", e.getMessage());
        }
        return ResponseEntity.ok(body);
    }
}
