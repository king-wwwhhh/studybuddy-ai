package org.example.auth;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验（HS256）。
 *
 * <p>密钥来自 {@code auth.jwt.secret}（生产必须用环境变量注入）；未配置时用进程随机密钥，
 * 后果是重启后旧 token 全部失效 —— 对开发够用，但日志会明确警告，避免上生产才发现。</p>
 */
@Component
public class JwtService {

    private static final Logger logger = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final long ttlMillis;

    public JwtService(@Value("${auth.jwt.secret:}") String secret,
                      @Value("${auth.jwt.ttl-hours:24}") long ttlHours) {
        byte[] material;
        if (secret == null || secret.isBlank()) {
            material = new byte[32];
            new java.security.SecureRandom().nextBytes(material);
            logger.warn("auth.jwt.secret 未配置：使用进程内随机密钥，重启后 token 全部失效（生产必须配置）");
        } else {
            material = secret.getBytes(StandardCharsets.UTF_8);
        }
        // HS256 要求密钥 ≥ 256 bit：不足时用 SHA-256 派生，够长则直接用
        if (material.length < 32) {
            try {
                material = MessageDigest.getInstance("SHA-256").digest(material);
            } catch (Exception e) {
                throw new IllegalStateException("无法派生 JWT 密钥", e);
            }
        }
        this.key = Keys.hmacShaKeyFor(material);
        this.ttlMillis = Math.max(1, ttlHours) * 3600_000L;
    }

    /** 签发 token：subject 为用户 id，附带 email 与 role 声明 */
    public String issue(long userId, String email, String role) {
        long now = System.currentTimeMillis();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("email", email)
                .claim("role", role)
                .issuedAt(new Date(now))
                .expiration(new Date(now + ttlMillis))
                .signWith(key)
                .compact();
    }

    /** 校验并解析；失败（过期/篡改/格式错）一律返回 empty，不抛给调用方 */
    public Optional<Claims> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Jwts.parser().verifyWith(key).build()
                    .parseSignedClaims(token).getPayload());
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public long ttlMillis() {
        return ttlMillis;
    }
}
