package org.example.auth;

/**
 * 当前请求的登录用户上下文（ThreadLocal）。
 *
 * <p>由 {@code AuthInterceptor} 在请求进入时写入、结束时清理。
 * Service 层取用户 id 一律从**这里**取，绝不信任前端传入的 userId —— 这是防越权的基本要求。</p>
 */
public final class AuthContext {

    /** 当前登录用户（未登录为 null） */
    public record Principal(long userId, String email, String role) {
        public boolean isAdmin() {
            return "ADMIN".equals(role);
        }
    }

    private static final ThreadLocal<Principal> HOLDER = new ThreadLocal<>();

    private AuthContext() {
    }

    public static void set(Principal principal) {
        HOLDER.set(principal);
    }

    public static Principal get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }

    /** 未登录时返回 null，由调用方决定是否要求登录 */
    public static Long userIdOrNull() {
        Principal p = HOLDER.get();
        return p == null ? null : p.userId();
    }

    public static boolean isAdmin() {
        Principal p = HOLDER.get();
        return p != null && p.isAdmin();
    }
}
