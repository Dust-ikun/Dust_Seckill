package com.dustikun.seckill.entity;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class Order {
    private Long id;
    private String orderNo;
    private Long userId;
    private Long stockId;
    private LocalDateTime createTime;
}
