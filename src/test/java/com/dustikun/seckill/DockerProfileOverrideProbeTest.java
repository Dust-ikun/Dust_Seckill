package com.dustikun.seckill;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

/**
 * 配置覆盖演示：把「合并后实际生效的值」打印出来。
 *
 * <p><b>它要回答的问题</b>：application.yaml 与 application-docker.yaml 里同一个
 * 配置项写了不同的值，运行时到底用哪一个？——这个问题不该靠记忆回答，
 * 因为「profile 覆盖基础配置」这条规则有若干例外（例如 profile 文件里的空值、
 * 列表类型是合并不是替换），靠记忆迟早出错。打印一次是最省事的确认方式。
 *
 * <p>运行：{@code mvn -o -B test -Dtest=DockerProfileOverrideProbeTest -DfailIfNoTests=false}
 *
 * <p><b>为什么显式关掉 MQ 与 Outbox</b>：本测试的目的只是「读配置的值」，
 * 而 RocketMQ 消费者在 {@code mq.enabled=true} 时会去连 NameServer，
 * 连不上就导致上下文加载失败 —— 那会把一个纯粹的配置问题伪装成环境问题。
 * 注意 {@code @Value} 的解析与 Bean 是否装配无关，因此关掉它们不影响本测试的结论。
 * 另外用 properties 而不是 profile，是为了不与其它 {@code @SpringBootTest}
 * 共享上下文缓存（上下文缓存以配置组合为键，配置不同会多创建一份上下文）。
 */
@SpringBootTest(properties = {
        "seckill.mq.enabled=false",
        "seckill.maintenance.enabled=false",
        "seckill.outbox.enabled=false"
})
@ActiveProfiles("docker")
class DockerProfileOverrideProbeTest {

    @Value("${spring.datasource.url}")
    private String datasourceUrl;

    @Value("${spring.datasource.password}")
    private String datasourcePassword;

    @Value("${spring.data.redis.port}")
    private String redisPort;

    @Value("${management.server.port}")
    private String managementPort;

    @Value("${management.server.address}")
    private String managementAddress;

    @Value("${seckill.mq.name-server}")
    private String mqNameServer;

    @Value("${seckill.outbox.max-retry}")
    private String outboxMaxRetry;

    @Value("${seckill.monitor.llm.api-key:}")
    private String llmApiKey;

    @Test
    void printEffectiveValues() {
        String line = "=".repeat(78);
        System.out.println("\n" + line);
        System.out.println("profile=docker 时，配置合并后【实际生效】的值");
        System.out.println(line);
        row("spring.datasource.url", datasourceUrl, "被 docker 覆盖（3306 → 3307）");
        row("spring.datasource.password", mask(datasourcePassword), "被 docker 覆盖（改为读环境变量）");
        row("spring.data.redis.port", redisPort, "被 docker 覆盖（改为读环境变量）");
        row("management.server.port", managementPort, "【未覆盖】沿用 application.yaml");
        row("management.server.address", managementAddress, "被 docker 覆盖（回环 → 0.0.0.0）");
        row("seckill.mq.name-server", mqNameServer, "【未覆盖】沿用 application.yaml");
        row("seckill.outbox.max-retry", outboxMaxRetry, "【未覆盖】沿用 application.yaml");
        row("seckill.monitor.llm.api-key", llmApiKey.isEmpty() ? "(空)" : mask(llmApiKey),
                "docker 独有（环境变量未设置时为空）");
        System.out.println(line);
        System.out.println("注意最后三行：docker profile 里【根本没写】这些项，");
        System.out.println("它们的值来自 application.yaml —— 这就是「覆盖层」的含义。");
        System.out.println(line + "\n");
    }

    private void row(String key, String value, String note) {
        System.out.printf("  %-32s = %-58s  # %s%n", key, value, note);
    }

    private String mask(String s) {
        if (s == null || s.length() <= 2) {
            return "**";
        }
        return s.charAt(0) + "*".repeat(Math.min(s.length() - 1, 6));
    }
}
