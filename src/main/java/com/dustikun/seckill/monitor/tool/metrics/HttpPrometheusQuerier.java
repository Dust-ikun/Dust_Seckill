package com.dustikun.seckill.monitor.tool.metrics;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * {@link PrometheusQuerier} 的 HTTP 实现（JDK 自带的 {@link HttpClient}，零新增依赖）。
 *
 * <h2>为什么不用 Micrometer 的 Prometheus 客户端或 PromQL 解析库</h2>
 * <p>
 * 本项目只需要「发一个查询、读回一组序列」这一件事，用官方的
 * 查询客户端会引入一个与本项目 Spring Boot 4.0.8 无关但同样需要版本对齐的依赖 ——
 * 而「为了 200 行代码引入一个需要长期跟版本的库」正是可行性报告冲突 1
 * 已经裁定过一次的取舍（不引入 Spring AI）。JDK 21 的 HttpClient 已经足够：
 * 连接池、超时、HTTP/1.1 都是内建能力。
 *
 * <h2>三个必须写清楚的实现细节</h2>
 * <ol>
 *   <li><b>PromQL 必须 URL 编码</b>。表达式里全是 {@code {} " / +} 这些字符，
 *       漏编码时 Prometheus 会返回 {@code 400 parse error}，而错误消息指向的是
 *       「表达式语法错」—— 会把人引向「我的 PromQL 写错了」这个错误方向。</li>
 *   <li><b>时间单位</b>：API 的 {@code start/end/step} 都是<b>秒</b>，
 *       而返回的采样时间戳也是秒（可带小数）。项目内部统一用毫秒，
 *       因此出入口各换算一次，且只在这一个类里换算。</li>
 *   <li><b>失败降噪</b>：Prometheus 不可达时，一次诊断会连续调用多个工具，
 *       若每次都打完整堆栈，日志会被同一条信息淹掉。这里用
 *       {@link #failureLogged} 保证「从通到不通」只报一次（恢复后重新允许报）。</li>
 * </ol>
 */
public final class HttpPrometheusQuerier implements PrometheusQuerier {

    private static final Logger log = LoggerFactory.getLogger(HttpPrometheusQuerier.class);

    /** 健康检查路径。Prometheus 内建，不消耗查询资源，也不会被 PromQL 语法影响 */
    private static final String HEALTH_PATH = "/-/healthy";

    private static final String QUERY_PATH = "/api/v1/query";

    private static final String RANGE_PATH = "/api/v1/query_range";

    private final String baseUrl;

    private final HttpClient httpClient;

    private final ObjectMapper objectMapper;

    private final Duration requestTimeout;

    /** 「已报过不可达」标志，避免同一故障刷满日志。恢复可达时复位 */
    private final AtomicBoolean failureLogged = new AtomicBoolean(false);

    public HttpPrometheusQuerier(String baseUrl, int timeoutMillis, ObjectMapper objectMapper) {
        // 去掉结尾的 '/'：拼接时统一由常量带来，否则会出现 '//api/v1/query'
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.objectMapper = objectMapper;
        this.requestTimeout = Duration.ofMillis(Math.max(200, timeoutMillis));
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(this.requestTimeout)
                // 跟随重定向：有人会在 Prometheus 前面放一个反向代理，而反代
                // 把 /api/v1/*  rewrite 到别处时通常带 302
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public List<PromSeries> queryRange(String expr, long startMillis, long endMillis, long stepSeconds) {
        if (expr == null || expr.isBlank()) {
            return List.of();
        }
        long step = Math.max(1, stepSeconds);
        String url = baseUrl + RANGE_PATH
                + "?query=" + encode(expr)
                + "&start=" + toSeconds(startMillis)
                + "&end=" + toSeconds(endMillis)
                + "&step=" + step;
        return execute(url, true);
    }

    @Override
    public List<PromSeries> queryInstant(String expr) {
        if (expr == null || expr.isBlank()) {
            return List.of();
        }
        return execute(baseUrl + QUERY_PATH + "?query=" + encode(expr), false);
    }

    @Override
    public boolean reachable() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + HEALTH_PATH))
                .timeout(requestTimeout)
                .header("Accept", "text/plain")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            boolean ok = response.statusCode() == 200;
            if (ok && failureLogged.compareAndSet(true, false)) {
                log.info("[MetricsTool] Prometheus 已恢复可达：{}", baseUrl);
            }
            return ok;
        } catch (IOException e) {
            noteFailure("健康检查失败：" + e);
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 全量指标名（{@code /api/v1/label/__name__/values}）。
     * <p>返回体形如 {@code {"status":"success","data":["go_gc_duration_seconds", ...]}}——
     * 注意 {@code data} 是一个<b>字符串数组</b>，不是通常的 {@code result} 结构。
     * 这一点与本类里其它解析路径不同，因此单独一个方法而不是复用 {@link #execute}。
     */
    @Override
    public Set<String> metricNames() {
        HttpRequest request = HttpRequest.newBuilder(
                        URI.create(baseUrl + "/api/v1/label/__name__/values"))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                noteFailure("读取指标名清单失败：HTTP " + response.statusCode());
                return Set.of();
            }
            JsonNode root = readTree(response.body());
            if (root == null || !"success".equals(root.path("status").asText())) {
                noteFailure("读取指标名清单失败：响应不是 success");
                return Set.of();
            }
            JsonNode data = root.path("data");
            if (!data.isArray()) {
                return Set.of();
            }
            Set<String> names = new java.util.LinkedHashSet<>(data.size() * 2);
            for (JsonNode name : data) {
                names.add(name.asText());
            }
            return names;
        } catch (IOException e) {
            noteFailure("读取指标名清单失败：" + e);
            return Set.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Set.of();
        } catch (RuntimeException e) {
            noteFailure("解析指标名清单失败：" + e);
            return Set.of();
        }
    }

    // ================================================================ 内部实现

    private List<PromSeries> execute(String url, boolean range) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (failureLogged.compareAndSet(true, false)) {
                log.info("[MetricsTool] Prometheus 已恢复可达：{}", baseUrl);
            }
            if (response.statusCode() != 200) {
                // 400 通常是表达式问题（例如引用了不存在的指标名会返回 success + 空结果，
                // 而语法错才会 400），因此这里把响应体前 300 字符带上 —— 它是唯一能
                // 区分「表达式写错」与「服务异常」的证据。
                noteFailure("HTTP " + response.statusCode() + "：" + abbreviate(response.body()));
                return List.of();
            }
            return parse(response.body(), range);
        } catch (IOException e) {
            noteFailure("请求失败：" + e);
            return List.of();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (RuntimeException e) {
            // 解析异常不该让整次诊断崩掉：它是「拿不到这条证据」，不是「工具坏了」。
            noteFailure("响应解析失败：" + e);
            return List.of();
        }
    }

    private List<PromSeries> parse(String body, boolean range) {
        JsonNode root = readTree(body);
        if (root == null) {
            return List.of();
        }
        if (!"success".equals(root.path("status").asText())) {
            noteFailure("查询未成功：" + abbreviate(root.path("error").asText("未知原因")));
            return List.of();
        }
        JsonNode result = root.path("data").path("result");
        if (!result.isArray()) {
            return List.of();
        }
        List<PromSeries> series = new ArrayList<>(result.size());
        for (JsonNode item : result) {
            Map<String, String> labels = readLabels(item.path("metric"));
            List<PromPoint> points = range ? readValues(item.path("values")) : readVector(item.path("value"));
            series.add(new PromSeries(labels, points));
        }
        return series;
    }

    private JsonNode readTree(String body) {
        try {
            return objectMapper.readTree(body);
        } catch (JacksonException e) {
            noteFailure("响应不是合法 JSON：" + e.getMessage());
            return null;
        }
    }

    private static Map<String, String> readLabels(JsonNode metric) {
        if (!metric.isObject()) {
            return Map.of();
        }
        Map<String, String> labels = new LinkedHashMap<>();
        // 【Jackson 3 的 API 变化】{@code fields()} 已被移除，等价方法是
        // {@code properties()}（返回 Set&lt;Map.Entry&lt;String, JsonNode&gt;&gt;）。
        // 这一点值得记下来：本项目用 Boot 4 + Jackson 3，
        // 网上与模型记忆里的绝大多数 Jackson 示例都是 2.x 的 {@code fields()}，
        // 照抄会得到「找不到符号」——而它看起来像是依赖没引入。
        for (Map.Entry<String, JsonNode> entry : metric.properties()) {
            labels.put(entry.getKey(), entry.getValue().asText());
        }
        return Collections.unmodifiableMap(labels);
    }

    /** 区间查询的 {@code values}：{@code [[ts,"v"],...]} */
    private static List<PromPoint> readValues(JsonNode values) {
        if (!values.isArray()) {
            return List.of();
        }
        List<PromPoint> points = new ArrayList<>(values.size());
        for (JsonNode pair : values) {
            PromPoint point = readPair(pair);
            if (point != null) {
                points.add(point);
            }
        }
        return points;
    }

    /** 瞬时查询的 {@code value}：{@code [ts,"v"]}（单个数组，不是数组的数组） */
    private static List<PromPoint> readVector(JsonNode value) {
        PromPoint point = readPair(value);
        return point == null ? List.of() : List.of(point);
    }

    /**
     * 读取一个 {@code [时间戳, 值]} 对。
     * <p>值在 Prometheus 的 JSON 里永远是<b>字符串</b>（为了表达 NaN / +Inf 这些
     * JSON 数字表达不了的值），因此不能用 {@code asDouble()} 直接读 ——
     * 它对 {@code "NaN"} 会返回 0.0，把「无数据」静默变成「零」。
     * 这正是本项目最忌讳的那类失效。
     */
    private static PromPoint readPair(JsonNode pair) {
        if (!pair.isArray() || pair.size() < 2) {
            return null;
        }
        double seconds = pair.get(0).asDouble();
        long millis = (long) Math.round(seconds * 1000.0);
        return new PromPoint(millis, parseValue(pair.get(1).asText()));
    }

    static double parseValue(String text) {
        if (text == null || text.isEmpty()) {
            return Double.NaN;
        }
        return switch (text) {
            case "NaN" -> Double.NaN;
            case "+Inf", "Inf" -> Double.POSITIVE_INFINITY;
            case "-Inf" -> Double.NEGATIVE_INFINITY;
            default -> {
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    yield Double.NaN;
                }
            }
        };
    }

    private void noteFailure(String reason) {
        if (failureLogged.compareAndSet(false, true)) {
            log.warn("[MetricsTool] Prometheus 查询失败（后续相同故障不再重复记录，恢复时会提示）："
                    + "baseUrl={}，原因={}", baseUrl, reason);
        } else {
            log.debug("[MetricsTool] Prometheus 查询仍然失败：{}", reason);
        }
    }

    private static String toSeconds(long millis) {
        // 保留三位小数（毫秒精度）。用 BigDecimal 而不是字符串拼接负数/整数，
        // 避免出现 "1.7E9" 这种科学计数法 —— Prometheus 会以 400 拒绝它。
        return java.math.BigDecimal.valueOf(millis, 3).toPlainString();
    }

    private static String encode(String expr) {
        return URLEncoder.encode(expr, StandardCharsets.UTF_8);
    }

    private static String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        String oneLine = text.replace('\n', ' ').trim();
        return oneLine.length() <= 300 ? oneLine : oneLine.substring(0, 300) + "…";
    }
}
