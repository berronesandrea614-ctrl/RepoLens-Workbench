package com.repolens.security;
import com.repolens.common.constants.ErrorCode;
import com.repolens.common.exception.BizException;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import jakarta.servlet.http.HttpServletRequest;

public class AuthUserIdArgumentResolver implements HandlerMethodArgumentResolver {
    public static final String ATTR_USER_ID = "authUserId";

    @Override
    public boolean supportsParameter(MethodParameter parameter) {
        return parameter.hasParameterAnnotation(AuthUserId.class)
                && Long.class.isAssignableFrom(parameter.getParameterType());
    }

    @Override
    public Object resolveArgument(MethodParameter parameter, ModelAndViewContainer mavContainer,
                                   NativeWebRequest webRequest, WebDataBinderFactory binderFactory) {
        HttpServletRequest req = (HttpServletRequest) webRequest.getNativeRequest();
        Object attr = req.getAttribute(ATTR_USER_ID);
        if (attr instanceof Long) return attr;
        if (attr instanceof Number) return ((Number) attr).longValue();
        if (attr instanceof String) return Long.parseLong((String) attr);
        throw new BizException(ErrorCode.UNAUTHORIZED, "未登录或 token 已过期");
    }
}
