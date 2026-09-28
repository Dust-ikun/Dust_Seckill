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

    /** 投递成功 */
    @Update("UPDATE seckill_outbox SET status = 'SENT', retry_count = retry_count + 1, last_error = NULL "
            + "WHERE id = #{id}")
    int markSent(@Param("id") Long id);

    /**
     * 批量标记整批已投出。
     * <p>
     * 与 {@link #markSent(Long)} 的唯一区别是把 N 条 UPDATE 压成 1 条 ——
     * 投递器按批投出消息后若还逐条更新，数据库往返次数与网络往返次数同阶，
     * 批量发送省下来的时间会被这里再吃掉一半。
     */
    @Update("<script>"
            + "UPDATE seckill_outbox SET status = 'SENT', retry_count = retry_count + 1, last_error = NULL "
            + "WHERE id IN "
            + "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>"
            + "</script>")
    int markSentBatch(@Param("ids") List<Long> ids);

    /** 投递失败但还有重试机会：累加次数并推后下次投递时间 */
    @Update("UPDATE seckill_outbox SET retry_count = retry_count + 1, next_retry_time = #{nextRetryTime}, "
            + "last_error = #{lastError} WHERE id = #{id}")
    int reschedule(@Param("id") Long id,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("lastError") String lastError);

    /** 重试次数用尽：标记为已放弃，等待人工介入（Redis 预扣此时应已回补） */
    @Update("UPDATE seckill_outbox SET status = 'FAILED', retry_count = retry_count + 1, "
            + "last_error = #{lastError} WHERE id = #{id}")
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    /** 待投递总数（欠账规模，监控与告警用） */
    @Select("SELECT COUNT(*) FROM seckill_outbox WHERE status = 'PENDING'")
    int countPending();

    /** 某活动尚未投出的数量——对账时作为「在途预扣下界」使用 */
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
