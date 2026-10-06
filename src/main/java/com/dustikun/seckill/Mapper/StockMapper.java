package com.dustikun.seckill.Mapper;

import com.dustikun.seckill.entity.Stock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface StockMapper {

    /**
     * 说明：本接口<b>只有扣减、没有回补</b>，这不是遗漏。
     * <p>
     * 数据库侧的每一次扣减都发生在 {@code @Transactional(rollbackFor = Exception.class)}
     * 方法内（{@code SeckillPersistenceService.confirm} / {@code persist}），
     * 「订单确认 ⟺ 库存扣减」在同一事务里，失败时由 Spring 自动回滚。
     * 因此不存在「需要手工把数据库库存加回去」的路径 —— 历史上曾有一个
     * {@code increase(num, id)} 无条件 UPDATE，既无调用方、其语义也会诱导出
     * 「绕过事务手工补库存」的写法，已删除。
     * <p>
     * Redis 侧的回补是另一回事（Redis 不是数据库事务的成员），那部分在
     * {@code StockCacheService#rollback} / {@code #restoreStockOnly} 里。
     */

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
}
