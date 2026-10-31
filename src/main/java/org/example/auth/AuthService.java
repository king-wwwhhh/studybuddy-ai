package org.example.auth;

import org.example.entity.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Optional;

/**
 * 认证业务：注册、密码登录、验证码登录、找回密码。
 *
 * <p>安全约定：</p>
 * <ul>
 *   <li>密码只存 BCrypt 哈希（cost 可配，默认 10），永不落明文；</li>
 *   <li>同一邮箱注册前必须通过验证码校验，防止用他人邮箱注册；</li>
 *   <li>登录失败统一返回"邮箱或密码错误"，不区分"用户不存在"与"密码错"（防账号枚举）；</li>
 *   <li>被禁用账号（status=DISABLED）登录直接拒绝。</li>
 * </ul>
 */
@Service
public class AuthService {

    @Autowired
    private org.example.service.SystemSettingService systemSettingService;

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    public static final String PURPOSE_REGISTER = "REGISTER";
    public static final String PURPOSE_RESET = "RESET";
    public static final String PURPOSE_LOGIN = "LOGIN";

    private final UserRepository users;
    private final VerificationCodeStore codes;
    private final MailService mailService;
    private final JwtService jwtService;
    private final BCryptPasswordEncoder encoder;

    public AuthService(UserRepository users, VerificationCodeStore codes, MailService mailService,
                       JwtService jwtService, @Value("${auth.bcrypt-cost:10}") int bcryptCost) {
        this.users = users;
        this.codes = codes;
        this.mailService = mailService;
        this.jwtService = jwtService;
        this.encoder = new BCryptPasswordEncoder(Math.max(4, Math.min(14, bcryptCost)));
    }

    /** 业务异常：由 Controller 统一转成 4xx */
    public static class AuthException extends RuntimeException {
        public final int status;

        public AuthException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    // ===================== 验证码 =====================

    /** 发送验证码；频率/额度超限抛 429 */
    public void sendCode(String rawEmail, String purpose) {
        // 关闭注册时，注册用途的验证码也不发（否则用户拿到码却注册不了，体验割裂）
        if ("REGISTER".equalsIgnoreCase(purpose) && !systemSettingService.isRegistrationOpen()) {
            throw new AuthException(403, "管理员已暂停新用户注册");
        }
        String email = VerificationCodePolicy.normalizeEmail(rawEmail);
        if (!VerificationCodePolicy.isValidEmail(email)) {
            throw new AuthException(400, "邮箱格式不正确");
        }
        String p = normalizePurpose(purpose);
        if (PURPOSE_REGISTER.equals(p) && users.findByEmail(email).isPresent()) {
            throw new AuthException(409, "该邮箱已注册，请直接登录");
        }
        long now = System.currentTimeMillis() / 1000;
        String code = codes.request(email, p, now);
        if (code == null) {
            throw new AuthException(429, "发送过于频繁，请 " + VerificationCodePolicy.RESEND_INTERVAL_SECONDS + " 秒后再试");
        }
        if (!mailService.sendCode(email, code, p)) {
            throw new AuthException(502, "验证码发送失败，请稍后重试");
        }
    }

    // ===================== 注册 / 登录 =====================

    public AuthDtos.AuthResponse register(AuthDtos.RegisterRequest req) {
        String email = VerificationCodePolicy.normalizeEmail(req.email());
        if (!VerificationCodePolicy.isValidEmail(email)) {
            throw new AuthException(400, "邮箱格式不正确");
        }
        if (req.code() == null || req.code().isBlank()) {
            throw new AuthException(400, "请输入验证码");
        }
        // 密码可选：不填则只能用验证码登录
        if (req.password() != null && !req.password().isBlank()
                && !VerificationCodePolicy.isStrongEnough(req.password())) {
            throw new AuthException(400, "密码需 8-64 位且同时包含字母和数字");
        }
        long now = System.currentTimeMillis() / 1000;
        if (!codes.verify(email, PURPOSE_REGISTER, req.code().trim(), now)) {
            throw new AuthException(400, "验证码错误或已过期");
        }
        if (users.findByEmail(email).isPresent()) {
            throw new AuthException(409, "该邮箱已注册，请直接登录");
        }
        // 用户名：必填、唯一、不能违规
        String username = (req.nickname() == null) ? "" : req.nickname().trim();
        String usernameError = UsernamePolicy.validate(username);
        if (usernameError != null) {
            throw new AuthException(400, usernameError);
        }
        if (users.existsUsername(username)) {
            throw new AuthException(409, "该用户名已被占用，请换一个");
        }
        String hash = (req.password() == null || req.password().isBlank())
                ? null : encoder.encode(req.password());

        // 注册一律是普通用户。管理员只能由已有管理员在后台「设为管理员」提升，
        // 保证权限收敛（首个注册者不再自动获得最高权限）。
        String role = User.ROLE_USER;
        // nickname 列保留兼容，但不再有"称呼"语义：直接存用户名
        long id = users.insert(email, username, hash, username, role);
        logger.info("新用户注册: id={} email={} username={} role={}", id, email, username, role);

        users.touchLogin(id);
        return toResponse(id, email, username, role);
    }

    /** 邮箱+密码登录 */
    public AuthDtos.AuthResponse loginWithPassword(AuthDtos.LoginRequest req) {
        // 账号既可以是邮箱也可以是用户名：含 @ 按邮箱查，否则按用户名查
        String account = req.email() == null ? "" : req.email().trim();
        Optional<User> found = users.findByEmailOrUsername(account);
        // 统一错误信息，避免账号枚举
        if (found.isEmpty() || found.get().getPasswordHash() == null
                || !encoder.matches(req.password() == null ? "" : req.password(), found.get().getPasswordHash())) {
            throw new AuthException(401, "账号或密码错误");
        }
        if (!found.get().isActive()) {
            throw new AuthException(403, "账号已被停用，请联系管理员");
        }
        return finishLogin(found.get());
    }

    /** 邮箱+验证码登录（免密码） */
    public AuthDtos.AuthResponse loginWithCode(AuthDtos.CodeLoginRequest req) {
        String email = VerificationCodePolicy.normalizeEmail(req.email());
        long now = System.currentTimeMillis() / 1000;
        if (!codes.verify(email, PURPOSE_LOGIN, req.code() == null ? "" : req.code().trim(), now)) {
            throw new AuthException(400, "验证码错误或已过期");
        }
        User user = users.findByEmail(email)
                .orElseThrow(() -> new AuthException(404, "该邮箱尚未注册"));
        return finishLogin(user);
    }

    /** 找回密码 */
    public void resetPassword(AuthDtos.ResetPasswordRequest req) {
        String email = VerificationCodePolicy.normalizeEmail(req.email());
        if (!VerificationCodePolicy.isStrongEnough(req.password())) {
            throw new AuthException(400, "密码需 8-64 位且同时包含字母和数字");
        }
        long now = System.currentTimeMillis() / 1000;
        if (!codes.verify(email, PURPOSE_RESET, req.code() == null ? "" : req.code().trim(), now)) {
            throw new AuthException(400, "验证码错误或已过期");
        }
        User user = users.findByEmail(email)
                .orElseThrow(() -> new AuthException(404, "该邮箱尚未注册"));
        users.updatePassword(user.getId(), encoder.encode(req.password()));
        logger.info("密码已重置: email={}", email);
    }

    /** 邮箱是否已注册（前端注册页实时提示用，不泄露其他信息） */
    public boolean emailExists(String rawEmail) {
        return users.findByEmail(VerificationCodePolicy.normalizeEmail(rawEmail)).isPresent();
    }

    public User requireUser(long userId) {
        return users.findById(userId)
                .orElseThrow(() -> new AuthException(401, "登录状态已失效，请重新登录"));
    }

    private AuthDtos.AuthResponse finishLogin(User user) {
        if (!user.isActive()) {
            throw new AuthException(403, "账号已被禁用，请联系管理员");
        }
        users.touchLogin(user.getId());
        return toResponse(user.getId(), user.getEmail(), user.getNickname(), user.getRole());
    }

    private AuthDtos.AuthResponse toResponse(long id, String email, String nickname, String role) {
        String token = jwtService.issue(id, email, role);
        return new AuthDtos.AuthResponse(token, jwtService.ttlMillis() / 1000, id, email, nickname, role);
    }

    private static String normalizePurpose(String purpose) {
        if (purpose == null || purpose.isBlank()) {
            return PURPOSE_REGISTER;
        }
        String p = purpose.trim().toUpperCase();
        return (PURPOSE_RESET.equals(p) || PURPOSE_LOGIN.equals(p)) ? p : PURPOSE_REGISTER;
    }
}
