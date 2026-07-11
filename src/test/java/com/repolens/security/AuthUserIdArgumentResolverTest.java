package com.repolens.security;

import com.repolens.common.exception.BizException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.context.request.ServletWebRequest;

import static org.junit.jupiter.api.Assertions.*;

class AuthUserIdArgumentResolverTest {

    private AuthUserIdArgumentResolver resolver;

    @BeforeEach
    void setup() {
        resolver = new AuthUserIdArgumentResolver();
    }

    @Test
    void resolveArgument_withLongAttribute_returnsLong() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthUserIdArgumentResolver.ATTR_USER_ID, 42L);
        NativeWebRequest webRequest = new ServletWebRequest(request);

        Object result = resolver.resolveArgument(null, null, webRequest, null);
        assertEquals(42L, result);
    }

    @Test
    void resolveArgument_withIntegerAttribute_returnsLong() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthUserIdArgumentResolver.ATTR_USER_ID, 7);
        NativeWebRequest webRequest = new ServletWebRequest(request);

        Object result = resolver.resolveArgument(null, null, webRequest, null);
        assertEquals(7L, result);
    }

    @Test
    void resolveArgument_withStringAttribute_returnsLong() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthUserIdArgumentResolver.ATTR_USER_ID, "99");
        NativeWebRequest webRequest = new ServletWebRequest(request);

        Object result = resolver.resolveArgument(null, null, webRequest, null);
        assertEquals(99L, result);
    }

    @Test
    void resolveArgument_withNoAttribute_throwsBizException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        NativeWebRequest webRequest = new ServletWebRequest(request);

        assertThrows(BizException.class, () ->
                resolver.resolveArgument(null, null, webRequest, null));
    }
}
