package com.repolens.common.exception;

import com.repolens.common.constants.ErrorCode;
import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final Integer code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    public BizException(ErrorCode errorCode, String message) {
        super(message);
        this.code = errorCode.getCode();
    }

    public BizException(Integer code, String message) {
        super(message);
        this.code = code;
    }
}
