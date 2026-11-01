package org.example.auth;

import io.jsonwebtoken.Claims;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * 轻量鉴权拦截器：JWT 校验 + 角色校验，不引入 Spring Security 全栈。
 *
 * <p>放行规则：</p>
 * <ul>
 *   <li>预检请求 {@code OPTIONS}（CORS）；</li>
 *   <li>白名单路径（登录/注册/验证码、静态资源、健康检查）；</li>
 *   <li>其余 {@code /api/**} 必须携带有效 token，否则 401；</li>
 *   <li>{@code /api/admin/**} 额外要求 role=ADMIN，否则 403。</li>
 * </ul>
 *
 * <p>注意：token 从 {@code Authorization: Bearer xxx} 或同名 Cookie 中读取，
 * 成功后写入 {@link AuthContext}，并与已有的 {@code ThreadLocal} 一样在 {@code afterCompletion} 清理，
 * 避免线程复用串号。</p>
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

    private static final Logger logger = LoggerFactory.getLogger(AuthInterceptor.class);

    /** 无需登录即可访问的路径前缀 */
    private static final List<String> WHITE_LIST = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/api/auth/send-code",
            "/api/auth/reset-password",
            "/api/auth/check-email",
            "/api/health",
            "/api/milvus/health"
    );

    private final JwtService jwtService;
    private final UserRepository users;

    public AuthInterceptor(JwtService jwtService, UserRepository users) {
        this.jwtService = jwtService;
        this.users = users;
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws Exception {
        String path = request.getRequestURI();
        AuthContext.clear();
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // 非 /api 前缀（前端静态资源、页面）一律放行
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (isWhiteListed(path)) {
            return true;
        }

        String token = resolveToken(request);
        Optional<Claims> claims = jwtService.parse(token);
        if (claims.isEmpty()) {
            writeError(response, 401, "未登录或登录已过期，请重新登录");
            return false;
        }
        Claims c = claims.get();
        long userId;
        try {
            userId = Long.parseLong(c.getSubject());
        } catch (Exception e) {
            writeError(response, 401, "登录凭证格式非法");
            return false;
        }
        var user = users.findById(userId).orElse(null);
        if (user == null || !user.isActive()) {
            writeError(response, 401, "账号已失效或被禁用，请重新登录");
            return false;
        }
        String role = user.getRole();

        if (path.startsWith("/api/admin/") && !"ADMIN".equals(role)) {
            logger.warn("越权访问后台接口: userId={} role={} path={}", userId, role, path);
            writeError(response, 403, "需要管理员权限");
            return false;
        }
        AuthContext.set(new AuthContext.Principal(userId, user.getEmail(), role));
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) {
        AuthContext.clear();
    }

    private static boolean isWhiteListed(String path) {
        for (String prefix : WHITE_LIST) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }

    /** 优先取 Authorization 头，其次取 Cookie，便于前端同时兼容两种接法 */
    private static String resolveToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7).trim();
        }
        if (request.getCookies() != null) {
            for (var cookie : request.getCookies()) {
                if ("token".equals(cookie.getName())) {
                    return cookie.getValue();
                }
            }
        }
        return null;
    }

    private static void writeError(HttpServletResponse response, int status, String message) throws Exception {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write("{\"success\":false,\"code\":" + status
                + ",\"message\":\"" + message + "\"}");
    }
}
