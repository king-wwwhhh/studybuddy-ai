package org.example.auth;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** OwnerSupport 单测：未登录归 SYSTEM、登录后取真实 id、可见性判定、SQL 片段。 */
class OwnerSupportTest {

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("未登录：currentUserId 返回 SYSTEM(0)，不抛异常")
    void anonymousIsSystem() {
        assertEquals(0L, OwnerSupport.SYSTEM_USER_ID);
        assertEquals(0L, OwnerSupport.currentUserId());
        assertFalse(OwnerSupport.hasLoginUser());
        assertFalse(OwnerSupport.isAdmin());
    }

    @Test
    @DisplayName("登录后：返回真实用户 id，管理员判定正确")
    void loggedIn() {
        AuthContext.set(new AuthContext.Principal(7L, "a@qq.com", "USER"));
        assertEquals(7L, OwnerSupport.currentUserId());
        assertTrue(OwnerSupport.hasLoginUser());
        assertFalse(OwnerSupport.isAdmin());

        AuthContext.set(new AuthContext.Principal(9L, "admin@qq.com", "ADMIN"));
        assertTrue(OwnerSupport.isAdmin());
    }

    @Test
    @DisplayName("可见性：本人数据可见、系统数据可见、他人数据不可见")
    void visibility() {
        AuthContext.set(new AuthContext.Principal(7L, "a@qq.com", "USER"));
        assertTrue(OwnerSupport.visible(7), "本人数据");
        assertTrue(OwnerSupport.visible(0), "系统公共数据");
        assertFalse(OwnerSupport.visible(8), "他人数据");
    }

    @Test
    @DisplayName("管理员也只按「本人 + 系统」可见，明细不越权（聚合统计走专门接口）")
    void adminSeesOwnAndSystemOnly() {
        AuthContext.set(new AuthContext.Principal(1L, "admin@qq.com", "ADMIN"));
        assertTrue(OwnerSupport.visible(1));
        assertTrue(OwnerSupport.visible(0));
        assertFalse(OwnerSupport.visible(2), "管理员看明细也需专门接口，不做隐式越权");
    }

    @Test
    @DisplayName("visibleScope 生成 SQL 片段，含本人与系统两个条件")
    void visibleScopeSql() {
        AuthContext.set(new AuthContext.Principal(7L, "a@qq.com", "USER"));
        String sql = OwnerSupport.visibleScope("user_id");
        assertEquals("(user_id = 7 OR user_id = 0)", sql);
        assertTrue(sql.contains("OR"));
    }

    @Test
    @DisplayName("ThreadLocal 清理后恢复未登录状态（防线程复用串号）")
    void clearResets() {
        AuthContext.set(new AuthContext.Principal(7L, "a@qq.com", "USER"));
        assertEquals(7L, OwnerSupport.currentUserId());
        AuthContext.clear();
        assertEquals(0L, OwnerSupport.currentUserId());
    }
}
