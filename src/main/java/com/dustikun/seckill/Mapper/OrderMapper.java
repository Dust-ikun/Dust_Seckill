package com.dustikun.seckill.Mapper;

import com.dustikun.seckill.entity.Order;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
@Mapper
public interface OrderMapper {
    @Insert("INSERT INTO orders(order_no, user_id, stock_id) " +
            "VALUES(#{orderNo}, #{userId}, #{stockId})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(Order order);
}
