package org.example.controller;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.File;

/**
 * 旧版用户端单页入口。
 *
 * <p>为什么单独写：Spring 的静态资源 handler 不会把 `/legacy/` 自动映射到
 * `static-legacy/index.html`（welcome-page 只对根路径生效），直接访问会 404。
 * 这里显式转发，并优先读源码目录（开发时改完刷新即生效）。</p>
 *
 * <p>该页面由前端路由守卫保护：未登录时 app.js 会被 Vue 登录页接管。</p>
 */
@RestController
public class LegacyPageController {

    private static final String[] CANDIDATES = {
            "src/main/resources/static-legacy/index.html",
            "classpath:static-legacy/index.html"
    };

    @GetMapping({"/legacy", "/legacy/"})
    public ResponseEntity<Resource> legacyIndex() {
        Resource res = resolve();
        if (res == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .contentType(MediaType.TEXT_HTML)
                .header("Cache-Control", "no-cache")
                .body(res);
    }

    private Resource resolve() {
        File f = new File(CANDIDATES[0]);
        if (f.isFile()) {
            return new FileSystemResource(f);
        }
        ClassPathResource cp = new ClassPathResource("static-legacy/index.html");
        return cp.exists() ? cp : null;
    }
}
