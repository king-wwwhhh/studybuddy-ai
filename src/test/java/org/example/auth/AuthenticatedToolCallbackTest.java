package org.example.auth;

import org.example.service.VectorScope;
import org.junit.jupiter.api.Test;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.annotation.Tool;

import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedToolCallbackTest {
    public static class Probe {
        private final CyclicBarrier barrier = new CyclicBarrier(2);
        @Tool(description = "Observe the caller identity")
        public String probe(String query) throws Exception {
            barrier.await(5, TimeUnit.SECONDS);
            return VectorScope.searchExprForCurrentUser();
        }
    }

    @Test void simultaneousToolCallsKeepSeparateIdentitiesAndCleanWorkerThreads() throws Exception {
        var delegate = ToolCallbacks.from(new Probe())[0];
        var a = new AuthenticatedToolCallback(delegate, new AuthContext.Principal(101, "a@test", "USER"));
        var b = new AuthenticatedToolCallback(delegate, new AuthContext.Principal(202, "b@test", "USER"));
        var pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                var first = pool.submit(() -> {
                    String result = a.call("{\"query\":\"q\"}");
                    assertNull(AuthContext.get());
                    return result;
                });
                var second = pool.submit(() -> {
                    String result = b.call("{\"query\":\"q\"}");
                    assertNull(AuthContext.get());
                    return result;
                });
                assertTrue(first.get(10, TimeUnit.SECONDS).contains("== 101"));
                assertTrue(second.get(10, TimeUnit.SECONDS).contains("== 202"));
            }
        } finally { pool.shutdownNow(); }
    }

    @Test void failureRestoresPreviousIdentity() {
        var previous = new AuthContext.Principal(303, "previous@test", "USER");
        AuthContext.set(previous);
        try {
            assertThrows(IllegalStateException.class, () -> AuthContext.callAs(
                    new AuthContext.Principal(101, "a@test", "USER"), () -> {
                        assertEquals(101L, AuthContext.userIdOrNull());
                        throw new IllegalStateException("tool failure");
                    }));
            assertEquals(previous, AuthContext.get());
        } finally { AuthContext.clear(); }
    }
}
