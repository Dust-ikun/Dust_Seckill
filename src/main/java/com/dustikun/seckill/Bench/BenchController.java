package com.dustikun.seckill.Bench;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对照实验端点：同一行热点库存，两种并发模型各打一遍。
 * <p>
 * 默认不注册（{@code seckill.bench.enabled} 必须为 true 才装配），压测时用命令行打开，
 * 因此它不会出现在正常运行的实例里，也不参与业务链路。
 * <p>
 * 用法：
 * <pre>
 *   POST /bench/reset?stockId=999001&count=5000000
 *   POST /bench/deduct?mode=cond&stockId=999001
 *   POST /bench/deduct?mode=opt&retry=3&stockId=999001
 *   GET  /bench/state?stockId=999001
 * </pre>
 */
@RestController
@RequestMapping("/bench")
@ConditionalOnProperty(name = "seckill.bench.enabled", havingValue = "true")
public class BenchController {

    private final BenchStockMapper mapper;

    public BenchController(BenchStockMapper mapper) {
        this.mapper = mapper;
    }

    @PostMapping("/reset")
    public Map<String, Object> reset(@RequestParam(defaultValue = "999001") long stockId,
                                     @RequestParam(defaultValue = "5000000") long count) {
        mapper.upsert(stockId, count);
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("stockId", stockId);
        r.put("count", count);
        return r;
    }

    @GetMapping("/state")
    public BenchStock state(@RequestParam(defaultValue = "999001") long stockId) {
        return mapper.selectById(stockId);
    }

    @PostMapping("/deduct")
    public Map<String, Object> deduct(@RequestParam(defaultValue = "cond") String mode,
                                      @RequestParam(defaultValue = "3") int retry,
                                      @RequestParam(defaultValue = "999001") long stockId) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("mode", mode);

        if ("cond".equalsIgnoreCase(mode)) {
            int n = mapper.deductCond(1L, stockId);
            r.put("result", n == 1 ? "ok" : "soldout");
            r.put("retries", 0);
            return r;
        }

        int max = Math.max(1, retry);
        for (int i = 0; i < max; i++) {
            BenchStock s = mapper.selectById(stockId);
            if (s == null || s.getCount() == null || s.getCount() < 1) {
                r.put("result", "soldout");
                r.put("retries", i);
                return r;
            }
            if (mapper.deductOpt(1L, stockId, s.getVersion()) == 1) {
                r.put("result", "ok");
                r.put("retries", i);
                return r;
            }
        }
        r.put("result", "giveup");
        r.put("retries", max - 1);
        r.put("retryLimit", max);
        return r;
    }
}
