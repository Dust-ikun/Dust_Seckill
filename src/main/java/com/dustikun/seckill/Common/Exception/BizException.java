package com.dustikun.seckill.Common.Exception;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException {

    private final String code;

    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }

    /**
     * 带上原始异常。
     * <p>
     * 用于「发生了 A 失败，紧接着补偿 B 也失败」这类需要把两件事一起告诉上游的场景：
     * 真正要排查的往往是 A，但真正要人工介入的是 B。丢掉 A 会让日志里只剩一个笼统的错误码。
     */
    public BizException(ErrorCode errorCode, Throwable cause) {
        super(errorCode.getMessage(), cause);
        this.code = errorCode.getCode();
    }
}
