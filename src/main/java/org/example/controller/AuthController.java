package org.example.controller;

import jakarta.servlet.http.HttpServletResponse;
import org.example.auth.AuthContext;
import org.example.auth.AuthDtos;
import org.example.auth.AuthService;
import org.example.auth.VerificationCodePolicy;
import org.example.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 认证接口：发送验证码、注册、登录、找回密码、当前用户。
 *
 * <p>除了 {@code send-code / register / login / reset-password / check-email}，
 * 其余端点都要带 token（由 {@code AuthInterceptor} 统一校验）。</p>
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private static final Logger logger = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 发送验证码：purpose = REGISTER / RESET / LOGIN */
    @PostMapping("/send-code")
    public ResponseEntity<?> sendCode(@RequestBody AuthDtos.SendCodeRequest req) {
        try {
            authService.sendCode(req.email(), req.purpose());
            return ok("验证码已发送，请查收邮箱（含垃圾箱）");
        } catch (AuthService.AuthException e) {
            return fail(e.status, e.getMessage());
        }
    }

    /** 注册（验证码必需，密码可选） */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody AuthDtos.RegisterRequest req, HttpServletResponse response) {
        try {
            AuthDtos.AuthResponse auth = authService.register(req);
            writeTokenCookie(response, auth.token());
            return ResponseEntity.ok(success(auth));
        } catch (AuthService.AuthException e) {
            return fail(e.status, e.getMessage());
        }
    }

    /** 邮箱+密码登录 */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody AuthDtos.LoginRequest req, HttpServletResponse response) {
        try {
            AuthDtos.AuthResponse auth = authService.loginWithPassword(req);
            writeTokenCookie(response, auth.token());
            return ResponseEntity.ok(success(auth));
        } catch (AuthService.AuthException e) {
            return fail(e.status, e.getMessage());
        }
    }

    /** 邮箱+验证码登录（免密码，适合临时设备） */
    @PostMapping("/login-code")
    public ResponseEntity<?> loginWithCode(@RequestBody AuthDtos.CodeLoginRequest req, HttpServletResponse response) {
        try {
            AuthDtos.AuthResponse auth = authService.loginWithCode(req);
            writeTokenCookie(response, auth.token());
            return ResponseEntity.ok(success(auth));
        } catch (AuthService.AuthException e) {
            return fail(e.status, e.getMessage());
        }
    }

    /** 找回密码（验证码 + 新密码） */
    @PostMapping("/reset-password")
    public ResponseEntity<?> resetPassword(@RequestBody AuthDtos.ResetPasswordRequest req) {
        try {
            authService.resetPassword(req);
            return ok("密码已重置，请用新密码登录");
        } catch (AuthService.AuthException e) {
            return fail(e.status, e.getMessage());
        }
    }

    /** 邮箱是否已注册（注册页/找回密码页实时提示） */
    @GetMapping("/check-email")
    public ResponseEntity<?> checkEmail(@RequestParam String email) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("email", VerificationCodePolicy.normalizeEmail(email));
        data.put("registered", authService.emailExists(email));
        return ResponseEntity.ok(Map.of("success", true, "data", data));
    }

    /** 当前登录用户（拦截器已校验 token） */
    @GetMapping("/me")
    public ResponseEntity<?> me() {
        Long userId = AuthContext.userIdOrNull();
        if (userId == null) {
            return fail(401, "未登录");
        }
        try {
            User user = authService.requireUser(userId);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("userId", user.getId());
            data.put("email", user.getEmail());
            data.put("nickname", user.getNickname());
            data.put("role", user.getRole());
            data.put("lastLoginAt", user.getLastLoginAt() == null ? null : user.getLastLoginAt().toString());
            return ResponseEntity.ok(Map.of("success", true, "data", data));
        } catch (AuthService.AuthException e) {
            return fail(e.status, e.getMessage());
        }
    }

    /** 登出：清除 Cookie（JWT 无状态，前端同时丢弃 token 即可） */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletResponse response) {
        response.addHeader("Set-Cookie",
                "token=; Path=/; Max-Age=0; HttpOnly; SameSite=Lax");
        return ok("已退出登录");
    }

    // ===================== 工具 =====================

    /** token 同时写 Cookie（HttpOnly，防 XSS 读取），前端也可继续用 Authorization 头 */
    private static void writeTokenCookie(HttpServletResponse response, String token) {
        response.addHeader("Set-Cookie",
                "token=" + token + "; Path=/; Max-Age=" + (7 * 24 * 3600) + "; HttpOnly; SameSite=Lax");
    }

    private static Map<String, Object> success(Object data) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("data", data);
        return body;
    }

    private static ResponseEntity<Map<String, Object>> ok(String message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("success", true);
        body.put("message", message);
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
