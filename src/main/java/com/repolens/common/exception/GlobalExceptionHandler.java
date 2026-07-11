package com.repolens.common.exception;

import com.repolens.common.constants.ErrorCode;
import com.repolens.common.result.Result;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.exceptions.PersistenceException;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindException;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(BizException.class)
    public ResponseEntity<Result<Void>> handleBizException(BizException ex) {
        return ResponseEntity.status(resolveStatus(ex.getCode()))
                .body(Result.failure(ex.getCode(), ex.getMessage()));
    }

    /**
     * @Valid / @Validated 校验失败：返回字段级错误信息（如 "q: must not be blank"），
     * 避免前端只拿到一句笼统的 message 无法定位到具体字段。
     */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class, ConstraintViolationException.class})
    public ResponseEntity<Result<Void>> handleValidationException(Exception ex) {
        String message = resolveValidationMessage(ex);
        if (!StringUtils.hasText(message)) {
            message = ErrorCode.BAD_REQUEST.getMessage();
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ErrorCode.BAD_REQUEST.getCode(), message));
    }

    /**
     * 请求本身就不合法：缺少必填 @RequestParam、参数类型不匹配、请求体无法解析。
     * 这些属于客户端错误，必须返回 400 而不是掉进通用 Exception 分支变成 500。
     */
    @ExceptionHandler({MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class,
            HttpMessageNotReadableException.class})
    public ResponseEntity<Result<Void>> handleBadRequestException(Exception ex) {
        String message;
        if (ex instanceof MissingServletRequestParameterException missing) {
            message = "缺少必填参数: " + missing.getParameterName();
        } else if (ex instanceof MethodArgumentTypeMismatchException mismatch) {
            message = "参数类型错误: " + mismatch.getName();
        } else {
            message = "请求体格式错误或无法解析";
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Result.failure(ErrorCode.BAD_REQUEST.getCode(), message));
    }

    /**
     * 数据库异常对前端统一脱敏，避免把 SQL、连接串或底层堆栈直接暴露出去。
     */
    @ExceptionHandler({DataAccessException.class, PersistenceException.class})
    public ResponseEntity<Result<Void>> handleDatabaseException(Exception ex) {
        log.error("Database access failed", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ErrorCode.DATABASE_ERROR.getCode(), ErrorCode.DATABASE_ERROR.getMessage()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Result<Void>> handleException(Exception ex) {
        log.error("Unhandled server exception", ex);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Result.failure(ErrorCode.SYSTEM_ERROR.getCode(), ErrorCode.SYSTEM_ERROR.getMessage()));
    }

    private String resolveValidationMessage(Exception ex) {
        if (ex instanceof MethodArgumentNotValidException manv) {
            return formatBindingResult(manv.getBindingResult());
        }
        if (ex instanceof BindException be) {
            return formatBindingResult(be.getBindingResult());
        }
        if (ex instanceof ConstraintViolationException cve) {
            return cve.getConstraintViolations().stream()
                    .map(v -> lastPathNode(v.getPropertyPath().toString()) + ": " + v.getMessage())
                    .collect(Collectors.joining("; "));
        }
        return ex.getMessage();
    }

    private String formatBindingResult(BindingResult bindingResult) {
        String fieldErrors = bindingResult.getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .collect(Collectors.joining("; "));
        if (StringUtils.hasText(fieldErrors)) {
            return fieldErrors;
        }
        return bindingResult.getGlobalErrors().stream()
                .map(ge -> ge.getObjectName() + ": " + ge.getDefaultMessage())
                .collect(Collectors.joining("; "));
    }

    private String lastPathNode(String propertyPath) {
        if (!StringUtils.hasText(propertyPath)) {
            return propertyPath;
        }
        int idx = propertyPath.lastIndexOf('.');
        return idx >= 0 ? propertyPath.substring(idx + 1) : propertyPath;
    }

    private HttpStatus resolveStatus(Integer code) {
        if (code == null) {
            return HttpStatus.INTERNAL_SERVER_ERROR;
        }
        return switch (code) {
            case 40000 -> HttpStatus.BAD_REQUEST;
            case 40100 -> HttpStatus.UNAUTHORIZED;
            case 40300 -> HttpStatus.FORBIDDEN;
            case 40400 -> HttpStatus.NOT_FOUND;
            case 40900 -> HttpStatus.CONFLICT;
            case 50010, 50020, 50000 -> HttpStatus.INTERNAL_SERVER_ERROR;
            default -> code >= 50000 ? HttpStatus.INTERNAL_SERVER_ERROR : HttpStatus.BAD_REQUEST;
        };
    }
}
