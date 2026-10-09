package com.dustikun.seckill.monitor.tool;

/**
 * 参数校验失败。由 {@link ToolArguments} 抛出，由 {@link ToolRegistry} 捕获并转成
 * {@link ToolStatus#REJECTED}。
 *
 * <h2>为什么不直接抛 {@link IllegalArgumentException}</h2>
 * <p>
 * 因为接收方需要区分「参数错」与「工具内部错」：前者要报 {@code REJECTED}，
 * 后者要报 {@code FAILED}（见 {@link ToolStatus} 的注释）。用一个专用异常类型，
 * 这个区分就是编译期的，而不是靠 {@code catch (Exception e)} 里的字符串判断。
 *
 * <h2>消息是写给谁的</h2>
 * <p>
 * 这条消息会原样出现在 Tool Trace 的 {@code error_message} 列里，也可能被回灌给 LLM
 * 让它自己纠正（批次 3 的选择）。因此它必须同时满足两个读者：
 * <ul>
 *   <li><b>人</b>：说清哪个参数、期望什么、实际收到什么；</li>
 *   <li><b>模型</b>：附上合法取值清单，让它下一次能直接改对，而不是再猜一轮。</li>
 * </ul>
 * 例：{@code 参数 limit=100000 超出范围 [1,200]。}
 */
public class ToolArgumentException extends RuntimeException {

    /** 出错的参数名；参数级错误之外的场景（如整体结构不对）为 {@code null} */
    private final String argumentName;

    public ToolArgumentException(String argumentName, String message) {
        super(message);
        this.argumentName = argumentName;
    }

    public String argumentName() {
        return argumentName;
    }

    /** 供 {@link ToolRegistry} 组装最终写入轨迹的消息（带参数名，便于快速定位） */
    public String describe() {
        return argumentName == null ? getMessage() : ("参数 " + argumentName + "：" + getMessage());
    }
}
