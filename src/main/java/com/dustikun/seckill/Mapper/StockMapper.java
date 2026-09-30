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

    /**
     * 数据库侧条件扣减（Redis 预扣的持久化账本，也是 Redis 故障时唯一的兜底路径）。
     * <p>
     * 条件必须是 {@code count >= #{num}}：若写成 {@code count > 0}，
     * 仅当 num == 1 时等价，一旦按 num > 1 扣减就会把库存扣成负数。
     */
    @Update("UPDATE stock SET count = count - #{num} WHERE id = #{id} AND count >= #{num}")
    int deduct(@Param("num") Long num, @Param("id") Long id);

    /**
     * 库存回补。用于 Redis 预扣成功、但数据库落库失败时，把已扣减的库存还回去，
     * 避免出现「Redis 扣了、DB 没扣」的少卖黑洞。
     */
    @Update("UPDATE stock SET count = count + #{num} WHERE id = #{id}")
    int increase(@Param("num") Long num, @Param("id") Long id);
}
