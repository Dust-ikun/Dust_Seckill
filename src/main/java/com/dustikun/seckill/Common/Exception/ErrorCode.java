package com.dustikun.seckill.Common.Exception;

import lombok.Getter;

@Getter
public enum ErrorCode {
    STOCK_NOT_ENOUGH("1000", "库存不足"),
    REPEAT_ORDER("1001", "请勿重复下单"),
    PARM_ERROR("1002", "参数错误");

    private final String code;
    private final String message;

    ErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }
}
