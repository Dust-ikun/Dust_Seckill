package com.dustikun.seckill.Service;

import com.dustikun.seckill.Common.Exception.BizException;
import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.Mapper.StockMapper;
import com.dustikun.seckill.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@Slf4j
public class SeckillService {
    @Autowired
    private StockMapper stockMapper;
    @Autowired
    private OrderMapper orderMapper;

    private final AtomicInteger successCount = new AtomicInteger(0);

    @Transactional(rollbackFor = Exception.class)
    public String seckill(Long userId, Long stockId) {
        int row = stockMapper.deduct(1L, stockId);
        if (row == 0) {
            throw new BizException(ErrorCode.STOCK_NOT_ENOUGH);
        }
        Order order = new Order();
        order.setUserId(userId);
        order.setOrderNo(UUID.randomUUID().toString().replace("-", ""));
        order.setStockId(stockId);
        order.setCreateTime(LocalDateTime.now());
        orderMapper.insert(order);
        successCount.incrementAndGet();
        return order.getOrderNo();
    }

    public int getSuccessCount() {
        return successCount.get();
    }
}
