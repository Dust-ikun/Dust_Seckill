package com.dustikun.seckill.Mapper;

import com.dustikun.seckill.entity.CompensateTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 待补偿任务表访问层。
 * <p>
 * 所有写操作都是参数绑定（{@code #{}}），不存在拼接 SQL。
 */
@Mapper
public interface CompensateTaskMapper {

    @Insert("INSERT INTO compensate_task(stock_id, user_id, order_no, num, type, reason, status, retry_count, next_retry_time) "
            + "VALUES(#{stockId}, #{userId}, #{orderNo}, #{num}, #{type}, #{reason}, 'PENDING', 0, #{nextRetryTime})")
    @Options(useGeneratedKeys = true, keyProperty = "id")
    int insert(CompensateTask task);

    /**
     * 取出到期的 PENDING 任务。走 {@code idx_status_next_retry} 复合索引，
     * 只扫描到期的一段，不会随表增长而全表扫描。
     */
    @Select("SELECT * FROM compensate_task WHERE status = 'PENDING' AND next_retry_time <= #{now} "
            + "ORDER BY next_retry_time LIMIT #{limit}")
    List<CompensateTask> selectDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /** 补偿成功：标记为已处理 */
    @Update("UPDATE compensate_task SET status = 'DONE', retry_count = retry_count + 1, last_error = NULL "
            + "WHERE id = #{id}")
    int markDone(@Param("id") Long id);

    /** 补偿仍失败：累加重试次数并推后下次重试时间 */
    @Update("UPDATE compensate_task SET retry_count = retry_count + 1, next_retry_time = #{nextRetryTime}, "
            + "last_error = #{lastError} WHERE id = #{id}")
    int reschedule(@Param("id") Long id,
                   @Param("nextRetryTime") LocalDateTime nextRetryTime,
                   @Param("lastError") String lastError);

    /** 重试次数用尽：标记为已放弃，等待人工介入 */
    @Update("UPDATE compensate_task SET status = 'FAILED', retry_count = retry_count + 1, "
            + "last_error = #{lastError} WHERE id = #{id}")
    int markFailed(@Param("id") Long id, @Param("lastError") String lastError);

    @Select("SELECT COUNT(*) FROM compensate_task WHERE status = #{status}")
    int countByStatus(@Param("status") String status);
}
