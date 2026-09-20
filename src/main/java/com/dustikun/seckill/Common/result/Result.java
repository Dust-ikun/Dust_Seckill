package com.dustikun.seckill.Common.result;

import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.io.Serializable;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Result<T> implements Serializable {
    private static final long SerialVersionUID = 1L;
    private String code;
    private String message;
    private T data;
    private boolean success;
    private long timestamp;

    public Result() {
        this.timestamp = System.currentTimeMillis();
    }

    public Result(String code, String message, T data, boolean success) {
        this.code = code;
        this.message = message;
        this.data = data;
        this.success = success;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> Result<T> success() {
        return new Result<>("0", "成功", null, true);
    }

    public static <T> Result<T> success(T data) {
        return new Result<>("0", "成功", data, true);
    }

    public static <T> Result<T> success(String message, T data) {
        return new Result<>("0", message, data, true);
    }

    public static <T> Result<T> fail(String code, String message) {
        return new Result<>(code, message, null, false);
    }

    public static <T> Result<T> fail(ErrorCode errorCode) {
        return new Result<>(errorCode.getCode(), errorCode.getMessage(), null, false);
    }
}
