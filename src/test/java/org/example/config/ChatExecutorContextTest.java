package org.example.config;

import org.example.auth.AuthContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 线程上下文传播回归测试。
 *
 * <p>踩过的坑：SSE 对话在 chat-sse-* 线程里收到请求，工具调用却被切到
 * boundedElastic 线程执行，ThreadLocal 里的登录用户丢失，
 * OwnerSupport.currentUserId() 退化为 0（SYSTEM），
 * 检索条件变成"只查系统数据"，用户自己的文档一条都召回不到。</p>
 *
 * <p>本测试锁住"显式登记请求用户"这条通路。</p>
 */
class ChatExecutorContextTest {

    @AfterEach
    void tearDown() {
        org.example.service.VectorScope.clearRequestUser();
        AuthContext.clear();
    }

    @Test
    @DisplayName("登记的请求用户在异步线程中仍可读取")
    void requestUserVisibleInOtherThread() throws Exception {
        org.example.service.VectorScope.setRequestUser(8L);

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Long> seen = new AtomicReference<>();
            pool.submit(() -> seen.set(org.example.service.VectorScope.requestUser())).get(5, TimeUnit.SECONDS);
            assertEquals(8L, seen.get(), "异步线程应读到登记的 userId");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("检索条件使用登记的用户，而不是退化为 0")
    void searchExprUsesOverride() {
        org.example.service.VectorScope.setRequestUser(8L);
        String expr = org.example.service.VectorScope.searchExprForCurrentUser();
        assertTrue(expr.contains("== 8"), "应包含登记的用户 id: " + expr);
        assertTrue(expr.contains("== 0"), "仍应包含系统公共数据: " + expr);
    }

    @Test
    @DisplayName("清理后回退到 ThreadLocal 的真实用户")
    void clearFallsBackToAuthContext() {
        org.example.service.VectorScope.setRequestUser(8L);
        org.example.service.VectorScope.clearRequestUser();

        AuthContext.set(new AuthContext.Principal(42L, "u@qq.com", "USER"));
        String expr = org.example.service.VectorScope.searchExprForCurrentUser();
        assertTrue(expr.contains("== 42"), "清理后应使用 AuthContext 的用户: " + expr);
    }

    @Test
    @DisplayName("都没有时退化为系统用户 0（保持可用）")
    void defaultsToSystem() {
        org.example.service.VectorScope.clearRequestUser();
        AuthContext.clear();
        String expr = org.example.service.VectorScope.searchExprForCurrentUser();
        assertTrue(expr.contains("== 0"), "应退化为系统数据: " + expr);
    }
}
