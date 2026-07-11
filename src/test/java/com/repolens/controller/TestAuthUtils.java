package com.repolens.controller;

import com.repolens.security.AuthUserId;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

/**
 * Shared test utility: provides a fixed-userId HandlerMethodArgumentResolver
 * for standaloneSetup controller tests (which bypass the JWT filter).
 */
public class TestAuthUtils {

    /** Returns an argument resolver that always resolves @AuthUserId to userId=1L. */
    public static HandlerMethodArgumentResolver fixedUserIdResolver() {
        return fixedUserIdResolver(1L);
    }

    public static HandlerMethodArgumentResolver fixedUserIdResolver(Long userId) {
        return new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthUserId.class);
            }
            @Override
            public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                           NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
                return userId;
            }
        };
    }
}
