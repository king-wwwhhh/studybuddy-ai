package org.example.auth;

/**
 * 当前操作用户解析（多用户隔离的统一入口）。
 *
 * <p>设计要点：</p>
 * <ul>
 *   <li><b>已登录</b>：返回 {@link AuthContext} 里的真实用户 id；</li>
 *   <li><b>未登录</b>：返回 {@link #SYSTEM_USER_ID}（0），而不是抛异常 ——
 *       因为 Agent 工具、定时重排、启动预热等场景确实没有 HTTP 会话，
 *       直接抛异常会让这些路径整体不可用；</li>
 *   <li><b>永不信任前端传入的 userId</b>：所有查询条件必须从这里取，这是防越权的底线。</li>
 * </ul>
 *
 * <p>系统数据（user_id = 0）在检索时按"公共可见"处理，与 {@link #SYSTEM_USER_ID} 配套使用。</p>
 */
public final class OwnerSupport {

    /** 无登录态的数据归属（旧数据、系统任务） */
    public static final long SYSTEM_USER_ID = 0L;

    private OwnerSupport() {
    }

    /** 当前用户 id；未登录返回 SYSTEM_USER_ID，绝不返回 null */
    public static long currentUserId() {
        Long id = AuthContext.userIdOrNull();
        return id == null ? SYSTEM_USER_ID : id;
    }

    /** 是否处于"有真实登录用户"的请求上下文 */
    public static boolean hasLoginUser() {
        return AuthContext.userIdOrNull() != null;
    }

    /** 是否管理员（未登录为 false） */
    public static boolean isAdmin() {
        return AuthContext.isAdmin();
    }

    /**
     * 数据可见性判定：本人数据，或系统公共数据（user_id=0）。
     * 管理员看"自己的 + 系统的"；管理员要看全部需走专门的统计接口（不给明细）。
     */
    public static boolean visible(long rowUserId) {
        long me = currentUserId();
        return rowUserId == me || rowUserId == SYSTEM_USER_ID;
    }

    /** 构造 SQL 可见性条件（只返回本人 + 系统数据） */
    public static String visibleScope(String column) {
        return "(" + column + " = " + currentUserId() + " OR " + column + " = " + SYSTEM_USER_ID + ")";
    }
}
