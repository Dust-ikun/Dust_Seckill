package com.dustikun.seckill.monitor.log;

import com.dustikun.seckill.monitor.core.MaskProperties;
import com.dustikun.seckill.monitor.core.Masker;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.Context;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 Logback 里的 {@link LogRingBufferAppender} 暴露成 Spring Bean，并装配 Logs Tool 的底座。
 *
 * <h2>为什么需要「把 Logback 的对象交给 Spring」，而不是让 Spring 自己 new 一个</h2>
 * <p>
 * 日志是在 Spring 容器起来<b>之前</b>就开始产生的（Spring 自己的启动日志就是），
 * 而 Logback 的配置来自 {@code logback-spring.xml} —— 一个 Spring 管不到的 XML 文件
 * （Spring Boot 只在渲染阶段接触它，之后装载由 Logback 自己完成）。
 * 于是这里必然有一个「谁持有对象」的接缝。两种接法的差别是决定性的：
 * <pre>
 *   ✗ Spring 自己 new LogRingBuffer，Logback 的 appender 也 new 一个
 *       → 日志写进 A，Logs Tool 查 B。不报错，永远查不到东西。
 *   ✓ Logback 持有唯一实例，Spring 把它取出来注册成 Bean
 *       → 读写同一份数据，且 Spring 侧可以正常做依赖注入与测试替身。
 * </pre>
 *
 * <h2>为什么从 Logback 的 Context 里按名字找，而不是自己反射 new</h2>
 * <p>
 * 名字 {@code RING_BUFFER} 与 {@code logback-spring.xml} 里
 * {@code <appender name="RING_BUFFER">} 是同一个常量。这样做的两层收益：
 * <ol>
 *   <li><b>拿到的就是 XML 里配好的那一个</b>（容量、级别都按 XML 生效），
 *       而不是一个用默认参数另建的实例；</li>
 *   <li><b>配置错了会立刻知道</b>：找不到时这里抛异常并指出「检查 logback-spring.xml」，
 *       而不是安静地降级成一个空缓冲 —— 那种降级会让 Logs Tool 永远返回 0 条，
 *       而「查不到日志」与「没有日志」在诊断上是完全不同的结论。</li>
 * </ol>
 *
 * <h2>与 {@code seckill.monitor.enabled} 的关系</h2>
 * <p>
 * 本配置类只在 {@code seckill.monitor.enabled=true}（默认）时生效，
 * 关掉它则没有 {@link LogSink} Bean，后续批次的 Tool 层自然拿不到日志数据。
 * 但 <b>appender 本身与这个开关无关</b>：它由 {@code logback-spring.xml} 无条件装载。
 * 这是刻意的 —— Logback 读不到 Spring 的配置属性，若想让 XML 跟随这个开关，
 * 就得引入 {@code <springProperty>} 或 JMX 系统属性，那份复杂度换来的只是
 * 「少存几 MB 内存」，而 appender 的 ring buffer 有硬上界，代价是确定的。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MonitorLogProperties.class, MaskProperties.class})
@ConditionalOnProperty(prefix = "seckill.monitor", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class MonitorLogConfiguration {

    private static final Logger log = LoggerFactory.getLogger(MonitorLogConfiguration.class);

    /**
     * appender 在 {@code logback-spring.xml} 里的名字。
     * <p>与 XML 里的 {@code <appender name="RING_BUFFER">} 必须逐字一致；
     * 改了一处必须改另一处，否则启动即报错（这是刻意的，见类注释）。
     */
    private static final String APPENDER_NAME = "RING_BUFFER";

    /**
     * 脱敏器。刻意做成独立 Bean 而不是 {@link LogSink} 的私有字段：
     * 后续批次的 DB / MQ / Business Tool 都要用它（任何 Tool 返回的文本
     * 在进入 LLM 之前都必须过一遍），一个实例共享一套规则才不会出现
     * 「日志脱敏了、DB 结果没脱敏」这种口径分裂。
     */
    @Bean
    public Masker monitorMasker(MaskProperties properties) {
        Masker masker = new Masker(properties);
        for (String warning : masker.patternWarnings()) {
            log.error("[Masker] 脱敏规则配置有误，该条规则将不生效：{}", warning);
        }
        return masker;
    }

    @Bean
    public LogSink logSink(ObjectProvider<MonitorLogProperties> propertiesProvider, Masker masker) {
        MonitorLogProperties props = propertiesProvider.getIfAvailable(
                () -> new MonitorLogProperties(null, null, null, null));
        LogSink sink = new LogSink(resolveBuffer(), props, masker);
        // 启动自检：用真实规则跑一遍已知的敏感样本，把「配了但没生效」当场暴露。
        // 放在这里而不是构造器里，是为了让「新建对象」保持无副作用。
        sink.probeMasking();
        log.info("[LogsTool] 日志底座就绪：容量={} 条，serviceName={}，单次默认返回 {} 条，"
                        + "单条上限 {} 字符，去重={}",
                sink.capacity(), props.normalized().serviceName(), props.normalized().queryLimit(),
                props.normalized().maxSampleChars(), props.normalized().dedupe());
        return sink;
    }

    /**
     * 从 Logback 的 LoggerContext 里取出已装载的 appender 并拿到它的缓冲。
     *
     * @throws IllegalStateException 找不到 appender 时。这属于<b>装配错误</b>，
     *         必须在启动时就炸出来 —— 见类注释第 2 条。
     */
    private LogRingBuffer resolveBuffer() {
        // 用 org.slf4j 的 LoggerFactory 而不是 Logback 的静态方法：
        // slf4j 2.x 通过 ServiceLoader 找到 Logback，返回的正是同一个 LoggerContext 实例，
        // 但它对「将来换掉日志实现」这件事是透明的。
        org.slf4j.ILoggerFactory factory = LoggerFactory.getILoggerFactory();
        if (!(factory instanceof LoggerContext context)) {
            throw new IllegalStateException("当前 SLF4J 绑定不是 Logback（实际为 "
                    + factory.getClass().getName() + "），Logs Tool 的日志底座依赖于 "
                    + "LogRingBufferAppender。请确认 classpath 里只有 logback-classic 一个绑定。");
        }

        Appender<ILoggingEvent> appender = findAppender(context);
        if (appender == null) {
            throw new IllegalStateException("在 Logback 中找不到名为 '" + APPENDER_NAME
                    + "' 的 appender。请检查 src/main/resources/logback-spring.xml 是否被加载"
                    + "（例如被 logging.config 指向了别的文件，或该文件被排除了）。");
        }
        if (appender instanceof LogRingBufferAppender ringAppender) {
            return ringAppender.getBuffer();
        }
        throw new IllegalStateException("Logback 中名为 '" + APPENDER_NAME + "' 的 appender 类型是 "
                + appender.getClass().getName() + "，不是 LogRingBufferAppender。"
                + "这通常是把 XML 里的 class 属性改错了。");
    }

    @SuppressWarnings("unchecked")
    private static Appender<ILoggingEvent> findAppender(LoggerContext context) {
        Appender<ILoggingEvent> found = context.getLogger(Logger.ROOT_LOGGER_NAME)
                .getAppender(APPENDER_NAME);
        if (found != null) {
            return found;
        }
        // 根 logger 上找不到时，再到 LoggerContext 里按名字找一次：
        // 这样即使将来有人把 appender 挂在某个具体 logger 上（而不是 root），
        // 本方法也仍然能工作，不必同步改代码。
        Appender<?> any = ((Context) context).getObject(APPENDER_NAME) instanceof Appender<?> a
                ? a : null;
        return any == null ? null : (Appender<ILoggingEvent>) any;
    }
}
