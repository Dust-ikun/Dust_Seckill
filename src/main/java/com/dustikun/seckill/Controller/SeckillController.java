package com.dustikun.seckill.Controller;

import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.Service.SeckillService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/seckill")
public class SeckillController {
    @Autowired
    SeckillService seckillService;

    @PostMapping
    public Result<?> seckill(@RequestParam Long userId, @RequestParam Long stockId) {
        String orderNo = seckillService.seckill(userId, stockId);
        return Result.success("下单成功", orderNo);
    }

    @GetMapping("/count")
    public Result<?> count() {
        int count = seckillService.getSuccessCount();
        return Result.success("成功扣减数", count);
    }
}