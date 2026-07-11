package com.repolens.common.exception;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.result.Result;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 直接构造异常并调用 handler 方法，验证参数类错误统一返回 400 + BAD_REQUEST，
 * 且带上可定位的参数名 / 字段级信息，而不是掉进通用 500。
 */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();

    @Test
    void missingRequestParam_shouldReturn400WithParamName() {
        var ex = new MissingServletRequestParameterException("q", "String");

        ResponseEntity<Result<Void>> response = handler.handleBadRequestException(ex);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals(ErrorCode.BAD_REQUEST.getCode(), response.getBody().getCode());
        Assertions.assertTrue(response.getBody().getMessage().contains("q"),
                "message should name the missing param: " + response.getBody().getMessage());
    }

    @Test
    void typeMismatch_shouldReturn400WithParamName() {
        var ex = new MethodArgumentTypeMismatchException(
                "abc", Integer.class, "depth", null, new NumberFormatException("bad"));

        ResponseEntity<Result<Void>> response = handler.handleBadRequestException(ex);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals(ErrorCode.BAD_REQUEST.getCode(), response.getBody().getCode());
        Assertions.assertTrue(response.getBody().getMessage().contains("depth"),
                "message should name the invalid param: " + response.getBody().getMessage());
    }

    @Test
    void unreadableBody_shouldReturn400() {
        var ex = new HttpMessageNotReadableException("malformed json", (org.springframework.http.HttpInputMessage) null);

        ResponseEntity<Result<Void>> response = handler.handleBadRequestException(ex);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals(ErrorCode.BAD_REQUEST.getCode(), response.getBody().getCode());
    }

    @Test
    void validationError_shouldReturn400WithFieldLevelMessage() {
        BindException ex = new BindException(new Object(), "searchRequest");
        ex.addError(new FieldError("searchRequest", "q", "must not be blank"));

        ResponseEntity<Result<Void>> response = handler.handleValidationException(ex);

        Assertions.assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        Assertions.assertEquals(ErrorCode.BAD_REQUEST.getCode(), response.getBody().getCode());
        String message = response.getBody().getMessage();
        Assertions.assertTrue(message.contains("q") && message.contains("must not be blank"),
                "message should be field-level: " + message);
    }
}
