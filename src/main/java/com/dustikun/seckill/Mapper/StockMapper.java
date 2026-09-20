package com.dustikun.seckill.Mapper;

import com.dustikun.seckill.entity.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StockMapper {
    @Select("SELECT * FROM stock WHERE id = #{id}")
    Stock selectById(@Param("id") Long id);

    @Update("UPDATE stock SET count = count - #{num} WHERE id = #{id} AND count > 0")
    int deduct(@Param("num") Long num, @Param("id") Long id);
}
