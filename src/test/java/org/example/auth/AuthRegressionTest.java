package org.example.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.example.service.SystemSettingService;
import org.example.support.SqliteTestDatabase;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AuthRegressionTest {
    @AfterEach void clear() { AuthContext.clear(); }

    private record Result(boolean allowed, int status) {}

    private Result request(AuthInterceptor interceptor, String token, String path) throws Exception {
        var status = new AtomicInteger(200);
        var writer = new PrintWriter(new StringWriter());
        var request = (HttpServletRequest) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{HttpServletRequest.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getRequestURI" -> path;
                    case "getMethod" -> "GET";
                    case "getHeader" -> "Authorization".equals(args[0]) ? "Bearer " + token : null;
                    default -> null;
                });
        var response = (HttpServletResponse) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class[]{HttpServletResponse.class}, (proxy, method, args) -> {
                    if ("setStatus".equals(method.getName())) status.set((Integer) args[0]);
                    if ("getWriter".equals(method.getName())) return writer;
                    return null;
                });
        return new Result(interceptor.preHandle(request, response, new Object()), status.get());
    }

    @Test void disabledAndDeletedUsersCannotReuseToken() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            db.user(1, "USER");
            var jwt = new JwtService("test-signing-secret-only", 24);
            var token = jwt.issue(1, "u1@example.test", "USER");
            var interceptor = new AuthInterceptor(jwt, new UserRepository(db.jdbc));
            assertTrue(request(interceptor, token, "/api/profile").allowed());
            db.jdbc.update("UPDATE users SET status='DISABLED' WHERE id=1");
            assertEquals(new Result(false, 401), request(interceptor, token, "/api/profile"));
            assertNull(AuthContext.get());
            db.jdbc.update("DELETE FROM users WHERE id=1");
            assertEquals(new Result(false, 401), request(interceptor, token, "/api/profile"));
        }
    }

    @Test void roleChangesApplyToExistingTokensAndDeniedRequestsLeaveNoPrincipal() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            db.user(1, "ADMIN");
            var jwt = new JwtService("test-signing-secret-only", 24);
            var token = jwt.issue(1, "u1@example.test", "ADMIN");
            var interceptor = new AuthInterceptor(jwt, new UserRepository(db.jdbc));
            assertTrue(request(interceptor, token, "/api/admin/stats").allowed());
            db.jdbc.update("UPDATE users SET role='USER' WHERE id=1");
            assertEquals(new Result(false, 403), request(interceptor, token, "/api/admin/stats"));
            assertNull(AuthContext.get());
            assertTrue(request(interceptor, token, "/api/profile").allowed());
            assertEquals("USER", AuthContext.get().role());
        }
    }

    @Test void closedRegistrationRejectsDefaultPurposesAndAlreadyIssuedCode() throws Exception {
        try (var db = new SqliteTestDatabase()) {
            var settings = new SystemSettingService(db.jdbc);
            var codes = new VerificationCodeStore();
            long now = System.currentTimeMillis() / 1000;
            var code = codes.request("new@example.test", "REGISTER", now);
            var auth = new AuthService(new UserRepository(db.jdbc), codes, null, new JwtService("test-key", 24), 4);
            SqliteTestDatabase.inject(auth, "systemSettingService", settings);
            settings.set(SystemSettingService.KEY_REGISTRATION_OPEN, "false");
            for (String purpose : new String[]{null, "", " register ", "UNKNOWN"}) {
                assertEquals(403, assertThrows(AuthService.AuthException.class,
                        () -> auth.sendCode("new@example.test", purpose)).status);
            }
            assertEquals(403, assertThrows(AuthService.AuthException.class, () -> auth.register(
                    new AuthDtos.RegisterRequest("new@example.test", code, "Password123", "validuser"))).status);
            assertEquals(0, db.jdbc.queryForObject("SELECT COUNT(*) FROM users", Integer.class));
            assertTrue(codes.verify("new@example.test", "REGISTER", code, now));
        }
    }
}
