package org.example.auth;

import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** JWT 单测：签发/解析往返、篡改拒绝、空 token、不同密钥互不通过。 */
class JwtServiceTest {

    @Test
    @DisplayName("issue/parse 往返：subject/email/role 都能取回")
    void roundTrip() {
        JwtService jwt = new JwtService("unit-test-secret-0123456789abcdef", 24);
        String token = jwt.issue(42L, "a@qq.com", "ADMIN");

        Optional<Claims> claims = jwt.parse(token);
        assertTrue(claims.isPresent());
        assertEquals("42", claims.get().getSubject());
        assertEquals("a@qq.com", claims.get().get("email", String.class));
        assertEquals("ADMIN", claims.get().get("role", String.class));
        assertEquals(24 * 3600, jwt.ttlMillis() / 1000);
    }

    @Test
    @DisplayName("被篡改的 token 解析失败（返回 empty，不抛异常）")
    void tamperedToken() {
        JwtService jwt = new JwtService("unit-test-secret-0123456789abcdef", 24);
        String token = jwt.issue(1L, "a@qq.com", "USER");
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("a") ? "bb" : "aa");
        assertTrue(jwt.parse(tampered).isEmpty());
    }

    @Test
    @DisplayName("空/格式错误 token 返回 empty；不同密钥签发的 token 互不通过")
    void invalidAndForeign() {
        JwtService jwt = new JwtService("unit-test-secret-0123456789abcdef", 24);
        assertTrue(jwt.parse(null).isEmpty());
        assertTrue(jwt.parse("").isEmpty());
        assertTrue(jwt.parse("not-a-jwt").isEmpty());

        JwtService other = new JwtService("another-secret-0123456789abcdefgh", 24);
        String foreign = other.issue(1L, "a@qq.com", "USER");
        assertTrue(jwt.parse(foreign).isEmpty(), "换密钥后旧 token 必须失效");
    }

    @Test
    @DisplayName("密钥不足 32 字节时自动派生，仍可正常签发校验")
    void shortSecretDerivation() {
        JwtService jwt = new JwtService("short", 1);
        String token = jwt.issue(7L, "b@qq.com", "USER");
        assertEquals("7", jwt.parse(token).orElseThrow().getSubject());
        assertEquals(3600, jwt.ttlMillis() / 1000);
    }

    @Test
    @DisplayName("未配置密钥时也能工作（使用进程内随机密钥）")
    void blankSecretStillWorks() {
        JwtService jwt = new JwtService("", 2);
        String token = jwt.issue(9L, "c@qq.com", "USER");
        assertTrue(jwt.parse(token).isPresent());
    }
}
