package org.example.auth;

/**
 * 认证接口的请求/响应体（集中放一处，避免散落的内部类）。
 */
public final class AuthDtos {

    private AuthDtos() {
    }

    /** 发送验证码 */
    public record SendCodeRequest(String email, String purpose) {
    }

    /** 注册（验证码 + 密码可选） */
    public record RegisterRequest(String email, String code, String password, String nickname) {
    }

    /** 邮箱+密码登录 */
    public record LoginRequest(String email, String password) {
    }

    /** 验证码登录（邮箱+验证码） */
    public record CodeLoginRequest(String email, String code) {
    }

    /** 找回密码 */
    public record ResetPasswordRequest(String email, String code, String password) {
    }

    /** 登录成功响应（含用户信息，不含敏感字段） */
    public record AuthResponse(String token, long expiresInSeconds, long userId, String email,
                               String nickname, String role) {
    }
}
