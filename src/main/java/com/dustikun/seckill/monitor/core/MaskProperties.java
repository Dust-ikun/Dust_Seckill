package com.dustikun.seckill.monitor.core;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 脱敏配置绑定（{@code seckill.monitor.mask.*}，SPEC 第 17.2 节）。
 *
 * <h2>为什么没有 {@code enabled} 总开关</h2>
 * <p>
 * 这不是遗漏。SPEC 第 17.2 节列了六类禁止进入 LLM 的信息（密码 / Token / 身份证 /
 * 银行卡 / 完整账户信息 / 连接串），它们是<b>合规约束</b>而不是性能选项。
 * 一个可以被关掉的合规开关，在赶工时一定会被关掉，而且关掉之后没有任何痕迹 ——
 * 日志里不会写「本次未脱敏」。因此这里只提供「脱到什么程度」的阈值，
 * 不提供「脱不脱」的选择。需要放开某一条时应当改代码并 code review。
 *
 * @param keepTailChars   标识符（userId / orderNo 等）保留末尾几位，
 *                        便于人工核对「是不是同一个人/同一单」而不泄露全量
 * @param secretKeywords  出现这些关键字时，其后的值整体替换。大小写不敏感
 * @param patterns        自定义脱敏正则（按 {@code 分组} 替换）。默认值里已含
 *                        手机号 / 身份证 / 银行卡 / JDBC 口令
 */
@ConfigurationProperties(prefix = "seckill.monitor.mask")
public record MaskProperties(
        Integer keepTailChars,
        List<String> secretKeywords,
        List<String> patterns
) {

    private static final int DEFAULT_KEEP_TAIL = 4;

    /**
     * 默认关键字。<b>与 application-docker.yaml 里那份保持一致</b>，
     * 但即使那份被删掉，这里也要有兜底 —— 默认不脱敏是最危险的一种「默认」。
     */
    private static final List<String> DEFAULT_KEYWORDS = List.of(
            "password", "passwd", "pwd", "token", "secret",
            "api-key", "apikey", "authorization", "cookie", "private-key");

    /**
     * 默认正则。每条是「一个带分组的模式」，替换时只替换第 1 组，
     * 保留 {@code password=} 这样的上下文 —— 让人看得出这里被脱敏了、脱的是什么字段。
     *
     * <p><b>为什么这里没有 {@code password=} 规则</b>：{@code password} 已经在
     * {@link #DEFAULT_KEYWORDS} 里，由关键字规则负责，而且那条规则认得
     * {@code password=} / {@code "password": "…"} 两种写法。
     * 再加一条 {@code (password=)([^&;,\s]+)} 是有害的而不是冗余的：
     * 关键字规则已经把值换成 {@code ***} 之后，这条正则会把 {@code ***} 当成
     * 「一个新的值」再脱一次。实测结果是 {@code password=******} ——
     * 星号个数看起来像某种信息，实际上只是「同一条规则被应用了两遍」的痕迹。
     *
     * <p>两条经验值得记下来：
     * <ul>
     *   <li><b>脱敏规则之间会互相叠加</b>，因此要么让规则互不重叠，要么让替换本身幂等
     *       （{@code Masker} 两条路都走了：既跳过已是 {@code ***} 的内容，也不重复配置同一语义的规则）；</li>
     *   <li><b>组值不能为空匹配</b>：{@code ([^&;,\s]+)} 会把 {@code ***} 整段吃掉，
     *       因为它对「值长什么样」没有任何约束。写脱敏正则时用 {@code (?![*])} 之类的前置断言
     *       明确排除占位符，比事后再去重更可靠。</li>
     * </ul>
     */
    private static final List<String> DEFAULT_PATTERNS = List.of(
            "\\b(1[3-9]\\d{9})\\b",
            "\\b(\\d{17}[\\dXx])\\b",
            "\\b(\\d{16,19})\\b");

    public MaskProperties normalized() {
        int tail = (keepTailChars == null || keepTailChars < 0) ? DEFAULT_KEEP_TAIL : keepTailChars;
        List<String> keywords = (secretKeywords == null || secretKeywords.isEmpty())
                ? DEFAULT_KEYWORDS : List.copyOf(secretKeywords);
        List<String> pats = (patterns == null || patterns.isEmpty())
                ? DEFAULT_PATTERNS : List.copyOf(patterns);
        return new MaskProperties(tail, keywords, pats);
    }
}
