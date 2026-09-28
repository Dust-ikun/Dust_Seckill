package com.dustikun.seckill;

import com.dustikun.seckill.Common.constant.CompensateType;
import com.dustikun.seckill.Mapper.CompensateTaskMapper;
import com.dustikun.seckill.Mapper.OrderMapper;
import com.dustikun.seckill.entity.CompensateTask;
import com.dustikun.seckill.entity.Order;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 实体映射回归测试。
 * <p>
 * 【为什么需要专门测这个】MyBatis 的「下划线列名 → 驼峰属性」映射由配置项
 * {@code mybatis.configuration.map-underscore-to-camel-case} 控制。这个配置一旦失效，
 * <b>不会报任何错</b>：SQL 照常执行、结果集照常返回，只是 {@code order_no} / {@code user_id}
 * 这类字段静默变成 {@code null}。
 * <p>
 * 本项目真实踩过一次，代价是库存静默丢失：
 * <pre>
 *   application.yaml 把配置写成了 spring.mybatis.configuration...（前缀错，从未生效）
 *   → CompensateTask 从库里读回来 stockId / userId 均为 null
 *   → 回补脚本被以 seckill:stock:{null} 调用，EXISTS 为 0
 *   → 脚本返回「活动已结束」，被当成「无需处理」正常结案
 *   → 库存永久丢失，任务状态却显示已完成
 * </pre>
 * 单值列（{@code id} / {@code count} / {@code type} / {@code num}）因为列名与属性名同形，
 * 恰好不受影响——这正是它难以被察觉的原因：连"部分字段正常"的假象都符合直觉。
 * <p>
 * 因此这里不测业务逻辑，只测「读回来的实体字段是不是都有值」这一件事。
 * 它跑得极快（不依赖 Redis 与 MQ），但能挡住一整类静默故障。
 */
@Slf4j
@SpringBootTest
@ActiveProfiles("review")
@DisplayName("实体映射：下划线列名必须映射到驼峰属性")
class SeckillEntityMappingTest {

    private static final long STOCK_ID = 980001L;
    private static final String ORDER_NO = "MAP-REGRESSION-1";

    @Autowired
    private OrderMapper orderMapper;
    @Autowired
    private CompensateTaskMapper compensateTaskMapper;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void reset() {
        clean();
    }

    @AfterEach
    void cleanUp() {
        clean();
    }

    private void clean() {
        jdbcTemplate.update("DELETE FROM orders WHERE stock_id = ?", STOCK_ID);
        jdbcTemplate.update("DELETE FROM compensate_task WHERE stock_id = ?", STOCK_ID);
    }

    @Test
    @DisplayName("Order：order_no / user_id / stock_id / status / create_time 都要映射上")
    void orderShouldMapAllSnakeCaseColumns() {
        Order inserted = new Order();
        inserted.setOrderNo(ORDER_NO);
        inserted.setUserId(700001L);
        inserted.setStockId(STOCK_ID);
        inserted.setStatus(Order.STATUS_CONFIRMED);
        assertEquals(1, orderMapper.insert(inserted), "订单应写入成功");
        assertNotNull(inserted.getId(), "自增主键应回填");

        Order loaded = orderMapper.selectByOrderNo(ORDER_NO);
        assertNotNull(loaded, "按单号应能查到刚写入的订单");
        assertEquals(ORDER_NO, loaded.getOrderNo(),
                "order_no 未映射：说明 map-underscore-to-camel-case 没生效");
        assertEquals(700001L, loaded.getUserId(),
                "user_id 未映射：说明 map-underscore-to-camel-case 没生效");
        assertEquals(STOCK_ID, loaded.getStockId(),
                "stock_id 未映射：说明 map-underscore-to-camel-case 没生效");
        assertEquals(Order.STATUS_CONFIRMED, loaded.getStatus(),
                "status 未映射：订单状态机是后续迁移的判定依据，读不回来会静默错判");
        assertNotNull(loaded.getCreateTime(), "create_time 未映射");
    }

    @Test
    @DisplayName("CompensateTask：stock_id / user_id / order_no / retry_count / next_retry_time 都要映射上")
    void compensateTaskShouldMapAllSnakeCaseColumns() {
        CompensateTask task = new CompensateTask();
        task.setStockId(STOCK_ID);
        task.setUserId(700002L);
        task.setOrderNo("MAP-REGRESSION-2");
        task.setNum(1);
        task.setType(CompensateType.ROLLBACK_ALL.name());
        task.setReason("实体映射回归测试");
        // 故意放到很久以前，保证在 selectDue 的排序里排在前面，不会被 LIMIT 截掉
        task.setNextRetryTime(LocalDateTime.now().minusYears(1));
        assertEquals(1, compensateTaskMapper.insert(task), "待补偿任务应写入成功");
        assertNotNull(task.getId(), "自增主键应回填");

        List<CompensateTask> due = compensateTaskMapper.selectDue(LocalDateTime.now(), 100);
        // 用 id 找到本行：id 是单值列，即使映射整体失效也能正确比较，
        // 因此这个「找」的动作本身不会因为被测缺陷而失败，失败点会准确地落在下面的字段断言上
        CompensateTask loaded = due.stream()
                .filter(t -> task.getId().equals(t.getId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "selectDue 未返回刚登记的任务，无法验证映射。id=" + task.getId()
                                + ", 返回条数=" + due.size()));

        assertEquals(STOCK_ID, loaded.getStockId(),
                "stock_id 未映射 —— 这正是导致回补脚本操作 seckill:stock:{null} 的那个缺陷");
        assertEquals(700002L, loaded.getUserId(),
                "user_id 未映射 —— 同上，会让回补作用到错误的键上");
        assertEquals("MAP-REGRESSION-2", loaded.getOrderNo(),
                "order_no 未映射 —— RESTORE_STOCK_ONLY 类型会因此完全无法重试");
        assertEquals(1, loaded.getNum(), "num 未映射");
        assertEquals(CompensateType.ROLLBACK_ALL.name(), loaded.getType(), "type 未映射");
        assertEquals(CompensateTask.STATUS_PENDING, loaded.getStatus(), "status 未映射");
        assertEquals(0, loaded.getRetryCount(), "retry_count 未映射（注意 0 与 null 的区别）");
        assertNotNull(loaded.getNextRetryTime(), "next_retry_time 未映射");
    }
}
