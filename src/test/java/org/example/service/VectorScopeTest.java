package org.example.service;

import org.example.auth.AuthContext;
import org.example.auth.OwnerSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** 向量隔离表达式单测（R5）：可见范围含"本人 + 系统 + 旧数据放行"，严格范围只含本人。 */
class VectorScopeTest {

    @AfterEach
    void clear() {
        AuthContext.clear();
    }

    @Test
    @DisplayName("检索范围：本人 or 系统(0) or 旧数据(无 user_id 字段)")
    void searchExprContainsThreeBranches() {
        AuthContext.set(new AuthContext.Principal(7L, "a@qq.com", "USER"));
        String expr = VectorScope.searchExprForCurrentUser();

        assertTrue(expr.contains("metadata[\"user_id\"] == 7"), expr);
        assertTrue(expr.contains("metadata[\"user_id\"] == 0"), expr);
        assertTrue(expr.contains("not exists"), "旧数据（无该字段）必须放行，否则改造前上传的资料全部检索不到: " + expr);
        assertTrue(expr.contains(" or "), "三个条件需用 or 串联: " + expr);
    }

    @Test
    @DisplayName("未登录时检索范围 = 系统数据 + 旧数据")
    void anonymousScope() {
        String expr = VectorScope.searchExprForCurrentUser();
        assertTrue(expr.contains("== 0"), expr);
        assertTrue(expr.contains("not exists"), expr);
    }

    @Test
    @DisplayName("严格范围（写入/删除）只含本人，不含系统与旧数据放行")
    void strictExprOnlyOwner() {
        String expr = VectorScope.strictExpr(7);
        assertEquals("metadata[\"user_id\"] == 7", expr);
        assertFalse(expr.contains(" or "), "严格范围不能有 or，否则会误删他人数据");
        assertFalse(expr.contains("not exists"), "严格范围不能放行无归属数据");
    }

    @Test
    @DisplayName("belongsTo：本人或系统可见，他人不可见")
    void belongsTo() {
        assertTrue(VectorScope.belongsTo(7, 7));
        assertTrue(VectorScope.belongsTo(OwnerSupport.SYSTEM_USER_ID, 7));
        assertFalse(VectorScope.belongsTo(8, 7));
    }

    @Test
    @DisplayName("不同用户的表达式互不相同（防止有人把当前用户写死）")
    void exprDiffersByUser() {
        AuthContext.set(new AuthContext.Principal(1L, "a@qq.com", "USER"));
        String e1 = VectorScope.searchExprForCurrentUser();
        AuthContext.set(new AuthContext.Principal(2L, "b@qq.com", "USER"));
        String e2 = VectorScope.searchExprForCurrentUser();
        assertNotEquals(e1, e2);
        assertTrue(e1.contains("== 1") && e2.contains("== 2"));
    }
}
