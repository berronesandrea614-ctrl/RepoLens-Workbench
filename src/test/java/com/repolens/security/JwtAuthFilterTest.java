package com.repolens.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.jsonwebtoken.Claims;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class JwtAuthFilterTest {

    private JwtService jwtService;
    private JwtAuthFilter filter;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setup() {
        jwtService = mock(JwtService.class);
        objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
        filter = new JwtAuthFilter(jwtService, objectMapper);
        // auth enabled by default
        ReflectionTestUtils.setField(filter, "authEnabled", true);
    }

    @Test
    void publicPath_loginEndpoint_passesThrough() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/auth/login");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
        verifyNoInteractions(jwtService);
    }

    @Test
    void missingAuthHeader_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void invalidToken_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        request.addHeader("Authorization", "Bearer invalid.token.here");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        when(jwtService.parseToken("invalid.token.here")).thenReturn(null);

        filter.doFilterInternal(request, response, chain);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void validToken_setsUserIdAttributeAndProceeds() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("1");
        when(jwtService.parseToken("valid.jwt.token")).thenReturn(claims);

        filter.doFilterInternal(request, response, chain);

        assertEquals(1L, request.getAttribute(AuthUserIdArgumentResolver.ATTR_USER_ID));
        verify(chain).doFilter(request, response);
        assertEquals(200, response.getStatus());
    }

    @Test
    void authDisabled_usesXUserIdHeader() throws Exception {
        ReflectionTestUtils.setField(filter, "authEnabled", false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        request.addHeader("X-User-Id", "5");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertEquals(5L, request.getAttribute(AuthUserIdArgumentResolver.ATTR_USER_ID));
        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtService);
    }

    @Test
    void authDisabled_noXUserIdHeader_defaultsToOne() throws Exception {
        ReflectionTestUtils.setField(filter, "authEnabled", false);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilterInternal(request, response, chain);

        assertEquals(1L, request.getAttribute(AuthUserIdArgumentResolver.ATTR_USER_ID));
        verify(chain).doFilter(request, response);
    }

    @Test
    void nonNumericSubject_returns401() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/repos");
        request.addHeader("Authorization", "Bearer valid.jwt.token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        Claims claims = mock(Claims.class);
        when(claims.getSubject()).thenReturn("not-a-number");
        when(jwtService.parseToken("valid.jwt.token")).thenReturn(claims);

        filter.doFilterInternal(request, response, chain);

        assertEquals(HttpServletResponse.SC_UNAUTHORIZED, response.getStatus());
        verify(chain, never()).doFilter(any(), any());
    }
}
