package com.dustikun.seckill.Mapper;

import com.dustikun.seckill.entity.OutboxMessage;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待投递消息表访问层。
 * <p>
 * 所有写操作都是参数绑定（{@code #{}}），不存在拼接 SQL。
 */
@Mapper
public interface OutboxMessageMapper {

    @Insert("INSERT INTO seckill_outbox(order_no, user_id, stock_id, num, status, retry_count, next_retry_time) "
            + "VALUES(#{orderNo}, #{userId}, #{stockId}, #{num}, 'PENDING', 0, #{nextRetryTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(OutboxMessage message);

    /**
     * 取出到期的待投递记录。走 {@code idx_outbox_status_next_retry} 复合索引，
     * 只扫描「到期待投递」的一段，不会随表增长退化成全表扫描。
     */
    @Select("SELECT * FROM seckill_outbox WHERE status = 'PENDING' AND next_retry_time <= #{now} "
            + "ORDER BY next_retry_time LIMIT #{limit}")
    List<OutboxMessage> selectPending(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 按单号查询（订单尚未落库时，用它判断这笔下单处在哪一步） */
    @Select("SELECT * FROM seckill_outbox WHERE order_no = #{orderNo}")
    OutboxMessage selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 投递成功。
     * <p>
     * 【为什么必须带 {@code status = 'PENDING'} 守卫】投递器不做抢占，同一条记录可能被
     * 多个实例（或本实例的下一轮）同时捞起来，因此这里的 UPDATE 必须是<b>有条件的</b>状态流转，
     * 而不是无条件赋值：
     * <ul>
     *   <li>记录已被别的实例投出（SENT）→ 影响 0 行，本次不重复计数；</li>
     *   <li>记录已被放弃（FAILED）→ 影响 0 行。这一条尤其重要：放弃路径已经把预扣归还了，
     *       若这里能把它改回 SENT，那条消息就可能在「库存已归还」的前提下被消费并确认订单。</li>
     * </ul>
     */
    @Update("UPDATE seckill_outbox SET status = 'SENT', retry_count = retry_count + 1, last_error = NULL "
            + "WHERE id = #{id} AND status = 'PENDING'")
    int markSent(@Param("id") Long id);

    /**
     * 批量标记整批已投出。
     * <p>
     * 与 {@link #markSent(Long)} 的唯一区别是把 N 条 UPDATE 压成 1 条 ——
     * 投递器按批投出消息后若还逐条更新，数据库往返次数与网络往返次数同阶，
     * 批量发送省下来的时间会被这里再吃掉一半。
     * <p>
     * 守卫语义与 {@link #markSent(Long)} 完全一致：批内某条已被别的实例改过状态时，
     * 它只是不进影响行数，不会被这次批量覆盖。
     */
    @Update("<script>"
            + "UPDATE seckill_outbox SET status = 'SENT', retry_count = retry_count + 1, last_error = NULL "
            + "WHERE status = 'PENDING' AND id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markSentBatch(@Param("ids") List<Long> ids);

    /**
     * 投递失败但还有重试机会：累加次数并推后下次投递时间。
     * <p>
     * 同样带 {@code status = 'PENDING'} 守卫：已经投出或已经放弃的记录不该再被推后重试
     * （前者会被重复投递，后者会绕过「已放弃」这个终态）。
     */
    @Update("UPDATE seckill_outbox SET retry_count = retry_count + 1, next_retry_time = #{nextRetryTime}, "
            + "last_error = #{lastError} WHERE id = #{id} AND status = 'PENDING'")
    int reschedule(@Param("id") Long id,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("lastError") String lastError);

    /**
     * 重试次数用尽：标记为已放弃（终态）。Redis 预扣的归还由 {@code compensate_task} 接管，
     * 见 {@code OutboxAbandonService}。
     * <p>
     * 【返回值的分量】这里的 1 / 0 不是「有没有更新成功」，而是<b>本次调用有没有真的赢下这次放弃</b>：
     * 守卫 {@code status = 'PENDING'} 保证并发下（多实例各自捞到同一条记录）只有一次翻转成功，
     * 调用方据此决定要不要登记归还待办 —— 影响 0 行意味着这条记录已被别的路径处理过，
     * 此时再登记一次归还就是重复归还（库存虚增）。
     */
    @Update("UPDATE seckill_outbox SET status = 'FAILED', retry_count = retry_count + 1, "
            + "last_error = #{lastError} WHERE id = #{id} AND status = 'PENDING'")
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    /** 待投递总数（欠账规模，监控与告警用） */
    @Select("SELECT COUNT(*) FROM seckill_outbox WHERE status = 'PENDING'")
    int countPending();

    /**
     * 某活动尚未投出的数量——对账的排空门控用它判断「这条链路还没有欠账」。
     * <p>
     * 只数 PENDING：FAILED 是终态，它不代表在途，因此<b>不会</b>把门控关上。
     * 这正是不该只靠门控判断健康的原因：已放弃记录既让门控放行，又让 AUTO 模式的在途数虚高
     * —— 对账为此单独有 {@code ReconcileReport.Status#ABANDONED_PENDING}。
     */
    @Select("SELECT COUNT(*) FROM seckill_outbox WHERE stock_id = #{stockId} AND status = 'PENDING'")
    int countPendingByStockId(@Param("stockId") Long stockId);

    /** 已放弃总数（需要人工介入的规模） */
    @Select("SELECT COUNT(*) FROM seckill_outbox WHERE status = 'FAILED'")
    int countFailed();

    /**
     * 归档清理：删除已投递且早于阈值的历史记录。
     * <p>
     * 分批发删除而不是一次删干净，避免长事务与主从延迟尖刺。
     * {@code LIMIT} 配合 {@code idx_outbox_status_next_retry} 前缀扫描，代价可控。
     */
    @Delete("DELETE FROM seckill_outbox WHERE status = 'SENT' AND update_time < #{before} LIMIT #{limit}")
    int purgeSentBefore(@Param("before") LocalDateTime before, @Param("limit") int limit);
}
