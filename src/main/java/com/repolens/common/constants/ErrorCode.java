package com.repolens.common.constants;

import lombok.Getter;

@Getter
public enum ErrorCode {
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未授权访问"),
    FORBIDDEN(40300, "无权限访问资源"),
    NOT_FOUND(40400, "资源不存在"),
    CONFLICT(40900, "资源冲突"),
    SYSTEM_ERROR(50000, "系统内部错误"),
    DATABASE_ERROR(50010, "数据库不可用"),
    EXTERNAL_SERVICE_ERROR(50020, "外部服务调用失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }
}
