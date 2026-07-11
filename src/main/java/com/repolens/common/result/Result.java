package com.repolens.common.result;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Result<T> {

    private Integer code;
    private String message;
    private T data;
    private LocalDateTime timestamp;

    public static <T> Result<T> success(T data) {
        return Result.<T>builder()
                .code(0)
                .message("OK")
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> Result<T> success(String message, T data) {
        return Result.<T>builder()
                .code(0)
                .message(message)
                .data(data)
                .timestamp(LocalDateTime.now())
                .build();
    }

    public static <T> Result<T> failure(Integer code, String message) {
        return Result.<T>builder()
                .code(code)
                .message(message)
                .timestamp(LocalDateTime.now())
                .build();
    }
}
