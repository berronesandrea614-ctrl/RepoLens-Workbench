package com.repolens.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.result.Result;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final AntPathMatcher MATCHER = new AntPathMatcher();
    private static final List<String> PUBLIC_PATHS = List.of(
            "/api/auth/login",
            "/api/auth/register",
            "/actuator/health/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/swagger-ui.html",
            "/doc.html",
            "/error",
            // MCP endpoint uses its own loopback+token auth (not user JWT).
            // Spring Security permitAll is set in SecurityConfig; this guard skips JWT parsing.
            "/mcp"
    );

    private final JwtService jwtService;
    private final ObjectMapper objectMapper;

    @Value("${repolens.auth.enabled:true}")
    private boolean authEnabled;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String path = request.getRequestURI();

        // Public paths always pass through
        if (isPublic(path)) {
            filterChain.doFilter(request, response);
            return;
        }

        if (!authEnabled) {
            // Compatibility mode: read X-User-Id header, default 1
            String header = request.getHeader("X-User-Id");
            long userId = (header != null && !header.isBlank()) ? Long.parseLong(header) : 1L;
            request.setAttribute(AuthUserIdArgumentResolver.ATTR_USER_ID, userId);
            // 同时置入 SecurityContext，否则 Spring Security 授权层仍判未认证(403)。
            UsernamePasswordAuthenticationToken compatAuth = new UsernamePasswordAuthenticationToken(
                    userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
            SecurityContextHolder.getContext().setAuthentication(compatAuth);
            filterChain.doFilter(request, response);
            return;
        }

        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            writeUnauthorized(response, "缺少认证 token");
            return;
        }

        String token = authHeader.substring(7);
        Claims claims = jwtService.parseToken(token);
        if (claims == null) {
            writeUnauthorized(response, "token 无效或已过期");
            return;
        }

        Long userId;
        try {
            userId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            log.warn("[Auth] JWT subject is not a valid user id: {}", claims.getSubject());
            writeUnauthorized(response, "token 无效");
            return;
        }
        request.setAttribute(AuthUserIdArgumentResolver.ATTR_USER_ID, userId);

        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
                userId, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
        SecurityContextHolder.getContext().setAuthentication(auth);

        filterChain.doFilter(request, response);
    }

    private boolean isPublic(String path) {
        return PUBLIC_PATHS.stream().anyMatch(p -> MATCHER.match(p, path));
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        Result<Void> body = Result.failure(ErrorCode.UNAUTHORIZED.getCode(), message);
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
