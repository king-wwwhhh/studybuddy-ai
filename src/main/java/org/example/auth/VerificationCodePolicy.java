package org.example.auth;

import java.security.SecureRandom;
import java.util.regex.Pattern;

/**
 * 邮箱验证码策略（纯逻辑，便于单测）。
 *
 * <p>安全要点（论文"安全设计"章节可直接引用）：</p>
 * <ul>
 *   <li>6 位数字，{@link SecureRandom} 生成，不用 {@code Math.random()}；</li>
 *   <li>有效期 5 分钟，一次性使用（校验成功即作废）；</li>
 *   <li>同一邮箱 60 秒内只能发一次（防刷），单日上限 10 次；</li>
 *   <li>同一验证码最多允许输错 5 次，超过即作废（防爆破）。</li>
 * </ul>
 */
public final class VerificationCodePolicy {

    /** 验证码位数 */
    public static final int DIGITS = 6;
    /** 有效期（秒） */
    public static final long TTL_SECONDS = 300;
    /** 同邮箱重发间隔（秒） */
    public static final long RESEND_INTERVAL_SECONDS = 60;
    /** 单邮箱单日发送上限 */
    public static final int DAILY_LIMIT = 10;
    /** 单验证码最大尝试次数 */
    public static final int MAX_ATTEMPTS = 5;

    private static final SecureRandom RANDOM = new SecureRandom();
    /** 邮箱格式：够用即可，不做 RFC 全量校验 */
    private static final Pattern EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    private VerificationCodePolicy() {
    }

    /** 生成 6 位数字验证码（保留前导零） */
    public static String generateCode() {
        int value = RANDOM.nextInt(1_000_000);
        return String.format("%06d", value);
    }

    /** 邮箱格式是否合法 */
    public static boolean isValidEmail(String email) {
        return email != null && email.length() <= 254 && EMAIL.matcher(email.trim()).matches();
    }

    /** 规范化邮箱：去空格 + 转小写（避免 A@x.com 与 a@x.com 注册成两个账号） */
    public static String normalizeEmail(String email) {
        return email == null ? "" : email.trim().toLowerCase();
    }

    /** 距上次发送是否已经过了冷却期 */
    public static boolean canResend(long lastSentEpochSecond, long nowEpochSecond) {
        if (lastSentEpochSecond <= 0) {
            return true;
        }
        return nowEpochSecond - lastSentEpochSecond >= RESEND_INTERVAL_SECONDS;
    }

    /** 是否还有剩余配额 */
    public static boolean withinDailyLimit(int sentToday) {
        return sentToday < DAILY_LIMIT;
    }

    /** 校验是否仍在有效期内 */
    public static boolean notExpired(long createdEpochSecond, long nowEpochSecond) {
        return nowEpochSecond - createdEpochSecond <= TTL_SECONDS;
    }

    /** 是否还允许继续尝试 */
    public static boolean attemptsLeft(int failedAttempts) {
        return failedAttempts < MAX_ATTEMPTS;
    }

    /** 密码强度：长度 ≥ 8，且同时包含字母与数字 */
    public static boolean isStrongEnough(String password) {
        if (password == null || password.length() < 8 || password.length() > 64) {
            return false;
        }
        boolean hasLetter = password.chars().anyMatch(Character::isLetter);
        boolean hasDigit = password.chars().anyMatch(Character::isDigit);
        return hasLetter && hasDigit;
    }
}
