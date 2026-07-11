package com.repolens.security;

import com.repolens.domain.entity.AppSettingEntity;
import com.repolens.mapper.AppSettingMapper;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Date;

@Slf4j
@Service
@RequiredArgsConstructor
public class JwtService {

    private static final String SETTING_KEY = "jwt.secret";
    private static final long EXPIRY_MS = 24L * 60 * 60 * 1000;

    @Value("${REPOLENS_JWT_SECRET:}")
    private String envSecret;

    private final AppSettingMapper appSettingMapper;

    private SecretKey signingKey;

    @PostConstruct
    public void init() {
        String secret = envSecret;
        if (secret == null || secret.isBlank()) {
            AppSettingEntity existing = appSettingMapper.selectById(SETTING_KEY);
            if (existing != null && existing.getV() != null) {
                secret = existing.getV();
                log.info("[Auth] Loaded JWT secret from app_setting.");
            } else {
                secret = generateSecret();
                AppSettingEntity entity = new AppSettingEntity();
                entity.setK(SETTING_KEY);
                entity.setV(secret);
                appSettingMapper.insert(entity);
                log.info("[Auth] Generated and stored new JWT secret in app_setting.");
            }
        }
        signingKey = Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    private String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public String generateToken(Long userId, String username) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + EXPIRY_MS);
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim("username", username)
                .issuedAt(now)
                .expiration(expiry)
                .signWith(signingKey)
                .compact();
    }

    /** Returns parsed claims or null if invalid/expired. */
    public Claims parseToken(String token) {
        try {
            return Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException e) {
            log.debug("[Auth] JWT parse failed: {}", e.getMessage());
            return null;
        }
    }
}
