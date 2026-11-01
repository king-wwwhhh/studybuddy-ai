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
 * <p>验证每次异步调用显式绑定身份，并在调用后恢复线程上下文。</p>
 */
class ChatExecutorContextTest {

    @AfterEach
    void tearDown() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("登记的请求用户在异步线程中仍可读取")
    void requestUserVisibleInOtherThread() throws Exception {
        var principal = new AuthContext.Principal(8L, "u@qq.com", "USER");

        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            AtomicReference<Long> seen = new AtomicReference<>();
            pool.submit(() -> AuthContext.runAs(principal, () -> seen.set(AuthContext.userIdOrNull())))
                    .get(5, TimeUnit.SECONDS);
            assertEquals(8L, seen.get(), "异步线程应读到登记的 userId");
            assertNull(pool.submit(AuthContext::get).get(5, TimeUnit.SECONDS), "任务结束后不能污染池中线程");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    @DisplayName("检索条件使用当前调用绑定的用户")
    void searchExprUsesOverride() {
        AuthContext.set(new AuthContext.Principal(8L, "u@qq.com", "USER"));
        String expr = org.example.service.VectorScope.searchExprForCurrentUser();
        assertTrue(expr.contains("== 8"), "应包含登记的用户 id: " + expr);
        assertTrue(expr.contains("== 0"), "仍应包含系统公共数据: " + expr);
    }

    @Test
    @DisplayName("嵌套调用结束后恢复原用户")
    void clearFallsBackToAuthContext() {
        AuthContext.set(new AuthContext.Principal(42L, "u@qq.com", "USER"));
        AuthContext.runAs(new AuthContext.Principal(8L, "b@qq.com", "USER"), () ->
                assertTrue(org.example.service.VectorScope.searchExprForCurrentUser().contains("== 8")));
        String expr = org.example.service.VectorScope.searchExprForCurrentUser();
        assertTrue(expr.contains("== 42"), "清理后应使用 AuthContext 的用户: " + expr);
    }

    @Test
    @DisplayName("都没有时退化为系统用户 0（保持可用）")
    void defaultsToSystem() {
        AuthContext.clear();
        String expr = org.example.service.VectorScope.searchExprForCurrentUser();
        assertTrue(expr.contains("== 0"), "应退化为系统数据: " + expr);
    }
}
