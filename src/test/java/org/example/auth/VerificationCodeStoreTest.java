package org.example.auth;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 验证码存储单测：一次性消费、冷却、错满作废、过期清理。 */
class VerificationCodeStoreTest {

    @Test
    @DisplayName("request/verify：正确验证码通过，且只能用一次")
    void oneTimeUse() {
        VerificationCodeStore store = new VerificationCodeStore();
        String code = store.request("a@qq.com", "REGISTER", 1_000);
        assertNotNull(code);
        assertTrue(store.verify("a@qq.com", "REGISTER", code, 1_010));
        assertFalse(store.verify("a@qq.com", "REGISTER", code, 1_020), "已消费，不能再用");
    }

    @Test
    @DisplayName("60 秒内重发被拒绝，之后可重发且旧码失效")
    void resendCooldown() {
        VerificationCodeStore store = new VerificationCodeStore();
        String first = store.request("a@qq.com", "REGISTER", 1_000);
        assertNull(store.request("a@qq.com", "REGISTER", 1_030), "冷却期内应拒绝");
        String second = store.request("a@qq.com", "REGISTER", 1_100);
        assertNotNull(second);
        // 旧码即便碰巧相同，也已被新记录替换
        assertTrue(store.verify("a@qq.com", "REGISTER", second, 1_110));
    }

    @Test
    @DisplayName("不同 purpose 互不干扰")
    void purposeIsolation() {
        VerificationCodeStore store = new VerificationCodeStore();
        String reg = store.request("a@qq.com", "REGISTER", 1_000);
        assertFalse(store.verify("a@qq.com", "RESET", reg, 1_010), "注册码不能用于重置密码");
        assertTrue(store.verify("a@qq.com", "REGISTER", reg, 1_010));
    }

    @Test
    @DisplayName("输错 5 次后作废，正确的码也不再通过（防爆破）")
    void attemptsLimit() {
        VerificationCodeStore store = new VerificationCodeStore();
        String code = store.request("a@qq.com", "REGISTER", 1_000);
        for (int i = 0; i < 5; i++) {
            assertFalse(store.verify("a@qq.com", "REGISTER", "000000", 1_010 + i));
        }
        assertFalse(store.verify("a@qq.com", "REGISTER", code, 1_030), "尝试次数用尽应作废");
    }

    @Test
    @DisplayName("过期验证码不通过；purgeExpired 清理后 size 归零")
    void expiry() {
        VerificationCodeStore store = new VerificationCodeStore();
        String code = store.request("a@qq.com", "REGISTER", 1_000);
        long expiredAt = 1_000 + VerificationCodePolicy.TTL_SECONDS + 1;
        assertFalse(store.verify("a@qq.com", "REGISTER", code, expiredAt));

        store.request("b@qq.com", "REGISTER", 5_000);
        assertEquals(1, store.size());
        store.purgeExpired(5_000 + VerificationCodePolicy.TTL_SECONDS + 1);
        assertEquals(0, store.size());
    }

    @Test
    @DisplayName("邮箱大小写/空格规范化后视为同一账号")
    void normalizedKey() {
        VerificationCodeStore store = new VerificationCodeStore();
        String code = store.request("  A@QQ.com ", "REGISTER", 1_000);
        assertNotNull(code);
        assertTrue(store.verify("a@qq.com", "REGISTER", code, 1_010));
    }
}
