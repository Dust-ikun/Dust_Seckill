package com.dustikun.seckill.Controller;

import com.dustikun.seckill.Mapper.StockMapper;
import com.dustikun.seckill.entity.Stock;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class StockControl {
    @Autowired
    private StockMapper stockMapper;

    @GetMapping("/stock/{id}")
    public Stock stock(@PathVariable Long id) {
        return stockMapper.selectById(id);
    }
}
