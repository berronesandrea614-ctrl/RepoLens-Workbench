package com.repolens.security;

import com.repolens.mapper.AppSettingMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class JwtServiceTest {

    @Mock
    private AppSettingMapper appSettingMapper;

    private JwtService jwtService;

    @BeforeEach
    void setup() {
        jwtService = new JwtService(appSettingMapper);
        ReflectionTestUtils.setField(jwtService, "envSecret", "test-secret-key-32bytes-minimum!!");
        jwtService.init();
    }

    @Test
    void generateAndParse_roundTrips_userId() {
        String token = jwtService.generateToken(42L, "alice");

        Claims claims = jwtService.parseToken(token);

        assertNotNull(claims);
        assertEquals("42", claims.getSubject());
        assertEquals("alice", claims.get("username", String.class));
    }

    @Test
    void tokenSignedWithDifferentKey_isRejected() {
        JwtService other = new JwtService(appSettingMapper);
        ReflectionTestUtils.setField(other, "envSecret", "another-different-key-32bytes!!!");
        other.init();

        String alienToken = other.generateToken(99L, "intruder");

        assertNull(jwtService.parseToken(alienToken));
    }

    @Test
    void expiredToken_isRejected() {
        SecretKey sameKey = Keys.hmacShaKeyFor(
                "test-secret-key-32bytes-minimum!!".getBytes(StandardCharsets.UTF_8));

        String expiredToken = Jwts.builder()
                .subject("1")
                .claim("username", "ghost")
                .issuedAt(new Date(System.currentTimeMillis() - 20_000))
                .expiration(new Date(System.currentTimeMillis() - 10_000))
                .signWith(sameKey)
                .compact();

        assertNull(jwtService.parseToken(expiredToken));
    }
}
