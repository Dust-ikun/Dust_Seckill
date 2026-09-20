package com.dustikun.seckill.Common.Exception;

import lombok.Getter;

@Getter
public class BizException extends RuntimeException{
    private final String code;
    public BizException(ErrorCode errorCode) {
        super(errorCode.getMessage());
        this.code = errorCode.getCode();
    }
}
