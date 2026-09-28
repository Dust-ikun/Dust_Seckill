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
     * 数据库侧条件扣减（阶段 2 的防超卖方案，阶段 3 起降级为「持久化账本 + Redis 故障时的兜底路径」）。
     * <p>
     * 修正记录：条件由 {@code count > 0} 改为 {@code count >= #{num}}。
     * 原写法仅当 num == 1 时才等价，一旦按 num > 1 扣减就会把库存扣成负数。
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
