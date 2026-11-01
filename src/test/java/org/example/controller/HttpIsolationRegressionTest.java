package org.example.controller;

import org.example.auth.*;
import org.example.service.MemoryService;
import org.example.support.SqliteTestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.boot.web.embedded.tomcat.TomcatServletWebServerFactory;
import org.springframework.boot.web.servlet.context.AnnotationConfigServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.server.ServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.net.URI;
import java.net.http.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real embedded HTTP server with an isolated SQLite DB; no model/mail/vector services. */
class HttpIsolationRegressionTest {
    @Configuration
    @EnableWebMvc
    static class Config {
        @Bean ServletWebServerFactory server() { return new TomcatServletWebServerFactory(0); }
        @Bean DispatcherServlet dispatcherServlet() { return new DispatcherServlet(); }
        @Bean SqliteTestDatabase database() throws Exception { return new SqliteTestDatabase(); }
        @Bean JwtService jwt() { return new JwtService("http-regression-signing-key", 1); }
        @Bean AuthInterceptor interceptor(SqliteTestDatabase db, JwtService jwt) {
            return new AuthInterceptor(jwt, new UserRepository(db.jdbc));
        }
        @Bean WebMvcConfigurer mvc(AuthInterceptor interceptor) {
            return new WebMvcConfigurer() {
                @Override public void addInterceptors(InterceptorRegistry registry) {
                    registry.addInterceptor(interceptor).addPathPatterns("/api/**");
                }
            };
        }
        @Bean MemoryService memory(SqliteTestDatabase db) { return new MemoryService(db.jdbc, null); }
        @Bean org.example.service.ProfileService profile(SqliteTestDatabase db) {
            return new org.example.service.ProfileService(db.jdbc);
        }
        @Bean MemoryController controller(MemoryService memory) throws Exception {
            var controller = new MemoryController();
            SqliteTestDatabase.inject(controller, "memoryService", memory);
            return controller;
        }
        @Bean AuthExceptionHandler errors() { return new AuthExceptionHandler(); }
        @Bean AdminProbe adminProbe() { return new AdminProbe(); }
    }

    @RestController
    static class AdminProbe {
        @GetMapping("/api/admin/probe") public java.util.Map<String, Boolean> probe() {
            return java.util.Map.of("success", true);
        }
    }

    @Test void realHttpRequestsEnforceOwnershipAccountStateAndCurrentRole() throws Exception {
        try (var context = new AnnotationConfigServletWebServerApplicationContext(Config.class)) {
            var db = context.getBean(SqliteTestDatabase.class);
            db.user(1, "ADMIN");
            db.user(2, "USER");
            var memory = context.getBean(MemoryService.class);
            AuthContext.runAs(new AuthContext.Principal(1, "a@test", "ADMIN"), () -> {
                memory.ensureSession("private", "chat", "private");
                memory.saveMessage("private", "user", "secret-for-user-one");
            });
            var jwt = context.getBean(JwtService.class);
            var a = jwt.issue(1, "a@test", "ADMIN");
            var b = jwt.issue(2, "b@test", "USER");
            var client = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + context.getWebServer().getPort();
            var own = get(client, base + "/api/memory/sessions/private", a);
            assertEquals(200, own.statusCode());
            assertTrue(own.body().contains("secret-for-user-one"));
            var foreign = get(client, base + "/api/memory/sessions/private", b);
            assertEquals(403, foreign.statusCode());
            assertFalse(foreign.body().contains("secret-for-user-one"));
            assertEquals(200, get(client, base + "/api/admin/probe", a).statusCode());
            db.jdbc.update("UPDATE users SET role='USER' WHERE id=1");
            int demoted = get(client, base + "/api/admin/probe", a).statusCode();
            assertEquals(403, demoted);
            db.jdbc.update("UPDATE users SET status='DISABLED' WHERE id=2");
            int disabled = get(client, base + "/api/memory/sessions", b).statusCode();
            assertEquals(401, disabled);
            System.out.println("HTTP_REGRESSION: own=200 foreign=403 demoted=" + demoted + " disabled=" + disabled);
        } finally { AuthContext.clear(); }
    }

    private static HttpResponse<String> get(HttpClient client, String url, String token) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }
}
