package org.example.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** 验证码策略单测：格式、生成、冷却、限额、过期、尝试次数、密码强度。 */
class VerificationCodePolicyTest {

    @Test
    @DisplayName("generateCode：6 位纯数字，含前导零仍为 6 位，且有随机性")
    void generateCode() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 200; i++) {
            String code = VerificationCodePolicy.generateCode();
            assertEquals(6, code.length(), "长度必须 6: " + code);
            assertTrue(code.matches("\\d{6}"), "必须是 6 位数字: " + code);
            seen.add(code);
        }
        assertTrue(seen.size() > 150, "200 次生成应高度分散，实际不同值 " + seen.size());
    }

    @Test
    @DisplayName("isValidEmail：常规地址通过，异常地址拒绝")
    void emailValidation() {
        assertTrue(VerificationCodePolicy.isValidEmail("a@qq.com"));
        assertTrue(VerificationCodePolicy.isValidEmail("first.last+tag@sub.example.cn"));
        assertFalse(VerificationCodePolicy.isValidEmail("a@qq"));
        assertFalse(VerificationCodePolicy.isValidEmail("a@@qq.com"));
        assertFalse(VerificationCodePolicy.isValidEmail("  "));
        assertFalse(VerificationCodePolicy.isValidEmail(null));
        assertFalse(VerificationCodePolicy.isValidEmail("x".repeat(250) + "@qq.com"));
    }

    @Test
    @DisplayName("normalizeEmail：去空格+小写，避免同一邮箱注册两个账号")
    void normalize() {
        assertEquals("a@qq.com", VerificationCodePolicy.normalizeEmail("  A@QQ.COM "));
        assertEquals("", VerificationCodePolicy.normalizeEmail(null));
    }

    @Test
    @DisplayName("canResend：首次可发，冷却期内拒绝，满 60 秒恢复")
    void resendCooldown() {
        assertTrue(VerificationCodePolicy.canResend(0, 1_000));
        assertFalse(VerificationCodePolicy.canResend(1_000, 1_030));
        assertFalse(VerificationCodePolicy.canResend(1_000, 1_059));
        assertTrue(VerificationCodePolicy.canResend(1_000, 1_060));
    }

    @Test
    @DisplayName("日限额与有效期边界")
    void limits() {
        assertTrue(VerificationCodePolicy.withinDailyLimit(0));
        assertTrue(VerificationCodePolicy.withinDailyLimit(9));
        assertFalse(VerificationCodePolicy.withinDailyLimit(10));

        assertTrue(VerificationCodePolicy.notExpired(1_000, 1_300));
        assertTrue(VerificationCodePolicy.notExpired(1_000, 1_300));
        assertFalse(VerificationCodePolicy.notExpired(1_000, 1_301));

        assertTrue(VerificationCodePolicy.attemptsLeft(4));
        assertFalse(VerificationCodePolicy.attemptsLeft(5));
    }

    @Test
    @DisplayName("密码强度：≥8 位且字母+数字")
    void passwordStrength() {
        assertTrue(VerificationCodePolicy.isStrongEnough("abc12345"));
        assertFalse(VerificationCodePolicy.isStrongEnough("abc1234"), "太短");
        assertFalse(VerificationCodePolicy.isStrongEnough("abcdefgh"), "缺数字");
        assertFalse(VerificationCodePolicy.isStrongEnough("12345678"), "缺字母");
        assertFalse(VerificationCodePolicy.isStrongEnough(null));
        assertFalse(VerificationCodePolicy.isStrongEnough("a1".repeat(33)), "超过 64 位");
    }
}
