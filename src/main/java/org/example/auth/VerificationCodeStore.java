package org.example.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 验证码存储（内存版）。
 *
 * <p>为什么用内存而不是 Redis：单机部署下一张 {@link ConcurrentHashMap} 就够，且不引入新依赖。
 * 代价是**重启后未使用的验证码失效**（对注册流程可接受），多实例部署需换成 Redis。</p>
 */
@Component
public class VerificationCodeStore {

    private static final Logger logger = LoggerFactory.getLogger(VerificationCodeStore.class);

    /** 一次验证码记录 */
    public static final class Entry {
        public final String code;
        public final long createdAt;
        public final String purpose;
        public int failedAttempts;
        public long lastSentAt;
        public int sentToday;

        Entry(String code, long createdAt, String purpose) {
            this.code = code;
            this.createdAt = createdAt;
            this.purpose = purpose;
            this.lastSentAt = createdAt;
            this.sentToday = 1;
        }
    }

    private final Map<String, Entry> store = new ConcurrentHashMap<>();

    /**
     * 申请发送：检查冷却与日限额，通过则生成新验证码。
     *
     * @return 生成的验证码；被拒绝返回 null
     */
    public synchronized String request(String email, String purpose, long now) {
        Entry old = store.get(key(email, purpose));
        if (old != null) {
            if (!VerificationCodePolicy.canResend(old.lastSentAt, now)) {
                logger.info("验证码重发过于频繁: {}", email);
                return null;
            }
            if (!VerificationCodePolicy.withinDailyLimit(old.sentToday)) {
                logger.warn("验证码日限额已达上限: {}", email);
                return null;
            }
        }
        String code = VerificationCodePolicy.generateCode();
        Entry entry = new Entry(code, now, purpose);
        if (old != null) {
            entry.sentToday = old.sentToday + 1;
            entry.lastSentAt = now;
        }
        store.put(key(email, purpose), entry);
        return code;
    }

    /** 校验并消费；成功即删除（一次性） */
    public synchronized boolean verify(String email, String purpose, String code, long now) {
        Entry entry = store.get(key(email, purpose));
        if (entry == null) {
            return false;
        }
        if (!VerificationCodePolicy.notExpired(entry.createdAt, now)
                || !VerificationCodePolicy.attemptsLeft(entry.failedAttempts)) {
            store.remove(key(email, purpose));
            return false;
        }
        if (entry.code.equals(code)) {
            store.remove(key(email, purpose));
            return true;
        }
        entry.failedAttempts++;
        return false;
    }

    /** 测试与运维用：清理过期/超限记录 */
    public synchronized void purgeExpired(long now) {
        store.entrySet().removeIf(e -> !VerificationCodePolicy.notExpired(e.getValue().createdAt, now)
                || !VerificationCodePolicy.attemptsLeft(e.getValue().failedAttempts));
    }

    public int size() {
        return store.size();
    }

    private static String key(String email, String purpose) {
        return VerificationCodePolicy.normalizeEmail(email) + "|" + purpose;
    }
}
