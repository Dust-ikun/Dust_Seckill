package com.dustikun.seckill.Controller;

import com.dustikun.seckill.Common.Exception.ErrorCode;
import com.dustikun.seckill.Common.result.Result;
import com.dustikun.seckill.Mapper.StockMapper;
import com.dustikun.seckill.entity.Stock;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/**
 * 库存查询接口（<b>数据库侧</b>）。
 *
 * <p><b>它与 {@code GET /seckill/remain} 的区别，就是这两个端点各自的全部存在理由</b>
 * <ul>
 *   <li>{@code /stock/{id}} —— 读 {@code stock} 表，是<b>账本</b>。
 *       它减去「在途预扣数」才等于真正可售的量，因此活动进行中它<b>不等于</b>可售余量；</li>
 *   <li>{@code /seckill/remain} —— 读 Redis，是<b>当下真实可售余量</b>。
 *       秒杀链路上先扣的永远是它，所以「现在还能不能抢到」要看它。</li>
 * </ul>
 * 两者不一致是<b>正常的中间状态</b>（有在途消息尚未落库），只有在整条链路排空后才应当相等；
 * 判断它们是否真的出了问题是 {@code GET /seckill/reconcile} 的职责，不是本端点。
 *
 * <p><b>本类不参与秒杀链路</b>：它只做一次单行主键查询，没有 Redis、没有事务、没有状态机，
 * 因此也不产生任何业务结果。它存在是为了让「账本」可以被直接看到——否则对账报告里的
 * {@code dbStock} 就只是一个无法独立复核的数字。
 */
@RestController
public class StockControl {

    private final StockMapper stockMapper;

    /**
     * 构造器注入，与项目中其它类保持一致（原先为字段级 {@code @Autowired}）。
     * <p>这里直接依赖 Mapper 而非 Service：查询是纯读操作，不需要经过业务封装——
     * 多一层反而会让「读的是数据库」这个事实变模糊。
     */
    public StockControl(StockMapper stockMapper) {
        this.stockMapper = stockMapper;
    }

    /**
     * 查询数据库侧库存。
     *
     * @param id 活动 ID
     * @return 统一 {@link Result} 包装；商品不存在时返回 {@link ErrorCode#STOCK_NOT_FOUND}。
     *         <p>响应契约与 {@code SeckillController} 对齐（此前直接返回 entity，
     *         且查不到时返回 HTTP 200 + body {@code null}，调用方拿不到错误码）。
     */
    @GetMapping("/stock/{id}")
    public Result<?> stock(@PathVariable Long id) {
        Stock stock = stockMapper.selectById(id);
        if (stock == null) {
            return Result.fail(ErrorCode.STOCK_NOT_FOUND);
        }
        return Result.success("数据库侧库存", stock);
    }
}
