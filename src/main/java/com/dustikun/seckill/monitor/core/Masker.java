package com.dustikun.seckill.monitor.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文本脱敏（SPEC 第 17.2 节）。Tool 返回的<b>任何</b>文本在进入 LLM 之前都要过这里。
 *
 * <h2>它防的不是「日志里恰好有密码」，而是三件更具体的事</h2>
 * <ol>
 *   <li><b>连接串</b>：一次数据库连接失败会把
 *       {@code jdbc:mysql://host/db?user=root&password=example-value-a1b2c3} 打进异常消息。
 *       这条日志本身完全正常，但它把生产口令送进了 LLM 的上下文。</li>
 *   <li><b>业务标识</b>：手机号 / 身份证 / 银行卡出现在参数校验失败的日志里。
 *       它们不是「系统的秘密」，但是<b>用户的</b>。</li>
 *   <li><b>配置回显</b>：{@code /actuator/env} 一类端点或 {@code Authorization} 头的调试输出。</li>
 * </ol>
 *
 * <h2>三条设计约束</h2>
 * <ul>
 *   <li><b>保留形状</b>：{@code password=example-value-a1b2c3} → {@code password=***}，
 *       而不是把整行删掉。排障的人需要知道「这里有一个口令字段」，
 *       也需要能判断「两行日志里的口令是不是同一个」（都变成 {@code ***} 之后这个判断失效，
 *       这是刻意的取舍：宁可少一点信息，不可多一份泄露）。</li>
 *   <li><b>标识符保留尾部</b>：{@code userId=13800138000} → {@code userId=***8000}。
 *       排障时「是不是同一个用户」比「是哪个用户」重要得多。</li>
 *   <li><b>失败要可见</b>：正则写错时<b>不能</b>静默跳过那条规则 ——
 *       一条被静默跳过的脱敏规则与没有这条规则等价，而使用者以为自己被保护着。
 *       因此编译失败会记录在 {@link #patternWarnings()} 里，并由调用方在结果中标注。</li>
 * </ul>
 *
 * <h2>线程安全</h2>
 * <p>
 * 所有字段在构造后不再变化；{@link Pattern} 本身是线程安全的。
 * 因此本类可安全地被多个 Tool 并发使用，无需额外同步。
 */
public final class Masker {

    /** 整体替换时用的占位符 */
    public static final String MASK = "***";

    /**
     * 关键字命中后「值」的边界。
     * <p>排除 {@code ? & ; ,} 与空白，是为了让
     * {@code jdbc:mysql://h/db?user=root&password=xx&useSSL=false}
     * 只被脱成 {@code ...&password=***&useSSL=false} ——
     * 若连 {@code &useSSL=false} 一起吃掉，排障时就再也看不出这条连接串连的是哪个库、
     * 开了什么参数，而那往往是判断「是不是连错了环境」的唯一线索。
     */
    private static final String VALUE_BOUNDARY = "[^&;,\\s\"'?}]+";

    private final int keepTailChars;

    private final List<Pattern> secretValuePatterns;

    /**
     * Bearer Token 规则，<b>必须排在 {@link #secretValuePatterns} 之前</b>。
     * <p>见构造器里的说明：关键字规则会把 {@code Bearer} 这一个词换掉，
     * 之后这条规则就失去了它的锚点。
     */
    private final List<Pattern> bearerPatterns;

    private final List<Pattern> customPatterns;

    private final List<String> patternWarnings = new ArrayList<>();

    public Masker(MaskProperties raw) {
        MaskProperties props = (raw == null ? new MaskProperties(null, null, null) : raw).normalized();
        this.keepTailChars = props.keepTailChars();

        this.secretValuePatterns = new ArrayList<>(props.secretKeywords().size());

        // 【Bearer Token 为什么单独一组、且必须排在关键字规则之前】
        // `Authorization: Bearer sk-abc123def456` 里关键字规则只吃掉 "Bearer" 这一个词
        // （值是按「一个 token」匹配的），真正的密钥原样留在后面；
        // 而输出里有个 *** 会让人以为已经脱敏了 —— 这是最危险的一类假象。
        //
        // 顺序是硬要求：关键字规则里的 `authorization` 会把它的值（即 `Bearer`）
        // 换成 ***，之后这条规则就再也看不到 `Bearer` 这个锚点了。
        // 因此它必须在关键字规则**之前**整段吃掉 `Bearer <token>`。
        //
        // 分组布局与关键字规则一致（组 1 + 组 2 保留、组 3 替换），共用 replaceKeywordValue：
        // 组 1 = "Bearer "，组 2 = 空白，组 3 = 密钥。
        this.bearerPatterns = new ArrayList<>(1);
        compile("(?i)(Bearer\\s+)([\\s\"']*)([A-Za-z0-9._~+/-]{8,}=*)", "bearer-token")
                .ifPresent(bearerPatterns::add);

        for (String keyword : props.secretKeywords()) {
            String quoted = Pattern.quote(keyword);
            // 【三处细节，每一处都对应一个真实的漏网场景】
            //  (?i)                 关键字大小写不敏感：Password= / PASSWORD= 都要命中
            //  ([\s\"']*[:=][\s\"']*) 同时接受 key=value 与 JSON 的 "key": "value"
            //                        —— 日志里两种写法都会出现，只认等号会漏掉 JSON
            //  (?!\*{3})            已经脱敏过的值不再重复脱敏，否则每次重放结果都会变
            String regex = "(?i)(" + quoted + ")([\\s\"']*[:=][\\s\"']*)(?!\\*{3})(" + VALUE_BOUNDARY + ")";
            compile(regex, "secret-keyword:" + keyword).ifPresent(secretValuePatterns::add);
        }

        this.customPatterns = new ArrayList<>(props.patterns().size());
        List<String> pats = props.patterns();
        for (int i = 0; i < pats.size(); i++) {
            compile(pats.get(i), "patterns[" + i + "]").ifPresent(customPatterns::add);
        }
    }

    private java.util.Optional<Pattern> compile(String regex, String label) {
        try {
            return java.util.Optional.of(Pattern.compile(regex));
        } catch (PatternSyntaxException e) {
            // 不抛异常：一条写错的正则不该让整个监控链路起不来。
            // 但必须留下痕迹（见 patternWarnings），由调用方标注在结果里。
            patternWarnings.add(label + " 正则无法编译：" + e.getDescription());
            return java.util.Optional.empty();
        }
    }

    /**
     * 脱敏一段文本。
     *
     * @param text 原文，可为 {@code null}
     * @return 脱敏后的文本；{@code null} 入参返回 {@code null}
     */
    public String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String out = text;

        // 顺序一：Bearer Token。<b>必须先于关键字规则</b>，理由见构造器注释。
        for (Pattern p : bearerPatterns) {
            out = replaceKeywordValue(p, out);
        }

        // 顺序二：关键字（password= / "token": "…"）
        for (Pattern p : secretValuePatterns) {
            out = replaceKeywordValue(p, out);
        }

        // 顺序三：自定义正则。第 1 组是「要脱掉的部分」，无分组时整体替换。
        // 【取值的纪律】把**值本身**放进第 1 组（写 `password=(\S+)`），
        // 而不是 `(password=)(\S+)` —— 后者会把 `password=` 这个字段名也换成 ***，
        // 读日志的人就看不出这里原本是什么字段了。
        for (Pattern p : customPatterns) {
            out = p.matcher(out).groupCount() >= 1
                    ? replaceGroup(p, out, 1, MASK)
                    : p.matcher(out).replaceAll(Matcher.quoteReplacement(MASK));
        }
        return out;
    }

    /**
     * 关键字规则的替换：<b>保留「键 + 分隔符」，只把值换成占位符</b>。
     *
     * <p>关键字正则的三组是 {@code (keyword)(separator)(value)}，只替换第 3 组会得到一个
     * 微妙错误的结果：{@code password=example-value-a1b2c3} 的匹配区间包含前两组，
     * 而替换只覆盖第 3 组的位置，于是输出里 {@code password=} 整个消失了，
     * 只剩一个孤零零的 {@code ***}。
     *
     * <p>这不是洁癖：日志里留下 {@code password=***} 时，人一眼就知道
     * 「这里原本有一个口令字段，值被脱敏了」；而只看到 {@code ***} 时，
     * 人会怀疑是日志本身被打坏了。诊断信息量完全不同。
     */
    private static String replaceKeywordValue(Pattern p, String input) {
        Matcher m = p.matcher(input);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            String value = m.group(3);
            if (value == null || isAlreadyMasked(value)) {
                // 【幂等性在这里也要判一次，不能只靠 replaceGroup 那一处】
                // 配置里的自定义规则（例如 (password=)([^&;,\s]+)）会先把值脱成 ***，
                // 之后关键字规则再次命中它。此时若还替换，就会把 *** 当作「一个新值」
                // 再脱一遍，得到 password=****** —— 星号个数看起来像信息，实际只是
                // 「同一段被脱了两遍」的痕迹。
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(input.length() + 16);
            }
            sb.append(input, last, m.start());
            // 组 1（键）+ 组 2（分隔符）原样保留：它们来自输入，不经替换语法解析，
            // 因此直接 append 是安全的。只有 replacement 需要 quoteReplacement
            // （见 replaceGroup 的说明）。
            sb.append(m.group(1)).append(m.group(2)).append(MASK);
            last = m.end(3);
        }
        if (sb == null) {
            return input;
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }

    /**
     * 只把<b>第 1 组</b>覆盖的那几个字符换成占位符，其余一个字符都不动。
     *
     * <h3>三段式拼接，以及 {@code last} 为什么要推到 {@code m.end()}</h3>
     * <pre>
     *   [0, m.start(group))      组之前的内容（保留）
     *   MASK                     组本身（替换掉）
     *   [m.end(group), m.end())  组之后、本次匹配之内的内容（<b>必须补回</b>）
     *   ── 之后从 m.end() 继续 ──
     * </pre>
     * <p>这里是本项目里唯一一个「写了三遍才对」的下标算法，三种错法都不会报错，
     * 只是输出悄悄变短或变乱，所以逐条记下来（以
     * {@code (password=)([^&;,\s]+)} 匹配 {@code password=abc123} 为例，
     * 第 1 组覆盖 {@code password=} 这 9 个字符）：
     * <ol>
     *   <li>用 {@code m.start()} 开头、只拼到 {@code m.end(group)} 就跳到 {@code m.end()}：
     *       丢掉第 1 组<b>之后</b>的部分 —— 输出从 {@code ***abc123} 变成光秃秃的 {@code ***}；</li>
     *   <li>三段都补，但 {@code last} 推到 {@code m.end()}：第 3 段会被拼两遍；</li>
     *   <li>把 {@code last} 推到 {@code m.end(group)} 且不补第 3 段：下一次拼接从组末尾开始，
     *       于是组与后续内容之间会少一截。</li>
     * </ol>
     * <p><b>结论</b>：三段都要，且 {@code last} 只能推进到 {@code m.end()}。
     * <p><b>想只脱值、保留字段名时，把值单独放进第 1 组</b>
     * （写 {@code password=(\S+)} 而不是 {@code (password=)(\S+)}）。
     * 后者会把 {@code password=} 本身也换成 {@code ***}，读日志的人就看不出
     * 这里原本是个口令字段了。
     *
     * <h3>为什么必须跳过已脱敏的内容</h3>
     * <p>多条规则会命中同一段文本。例如 {@code password=example-value-a1b2c3} 先被关键字规则脱成
     * {@code password=***}，接着配置里那条 {@code (password=)([^&;,\s]+)} 又把 {@code ***}
     * 当成一个值再脱一遍，结果是 {@code password=******}。它同样不报错，
     * 只是每多一条规则就多三个星号 —— 而「星号个数」看起来像是某种信息。
     */
    private static String replaceGroup(Pattern p, String input, int group, String replacement) {
        Matcher m = p.matcher(input);
        StringBuilder sb = null;
        int last = 0;
        while (m.find()) {
            String matched = m.group(group);
            if (matched == null || isAlreadyMasked(matched)) {
                continue;
            }
            if (sb == null) {
                sb = new StringBuilder(input.length() + 16);
            }
            sb.append(input, last, m.start(group));
            // 【必须 quoteReplacement】占位符是 "***"，而 replaceAll / appendReplacement
            // 会把替换串当成<b>正则替换语法</b>解析：`*` 是量词、`$` 是分组引用。
            // 本项目第一版就踩了这个坑：`*` 被当成量词后输出变成 `******`，
            // 不报错、看起来还挺合理，但那个数字已经没有任何含义了。
            sb.append(Matcher.quoteReplacement(replacement));
            sb.append(input, m.end(group), m.end());
            last = m.end();
        }
        if (sb == null) {
            return input;
        }
        sb.append(input, last, input.length());
        return sb.toString();
    }

    /**
     * 判断一段文本是否已经被脱敏过。
     * <p>判据是「整段都由 {@code *} 组成」——这正是 {@link #MASK} 的形状。
     * 不去比对具体的占位符常量，是为了让「改占位符样式」这件事不会静默破坏这条幂等性。
     *
     * <p>两条替换路径（关键字规则与自定义规则）<b>都要用它</b>。
     * 只在一处判是不够的：两条路径的先后顺序决定了「谁先脱、谁后看到 ***」，
     * 而配置是可改的 —— 今天顺序对，明天加一条规则就错了。
     */
    private static boolean isAlreadyMasked(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) != '*') {
                return false;
            }
        }
        return true;
    }

    /**
     * 脱敏一个标识符，<b>保留末尾若干位</b>。
     *
     * <p>用于 userId / orderNo 这类「需要能对上号、但不能全量外发」的字段。
     * 排障时「是不是同一单」远比「是哪一单」重要，保留尾 4 位让前者仍然可判断。
     *
     * <p>长度不足 {@code keepTailChars + 1} 时整体替换为 {@code ***}：
     * 保留尾部在一个 3 位数字上等于全部泄露。
     */
    public String maskIdentifier(String id) {
        if (id == null || id.isEmpty()) {
            return id;
        }
        if (keepTailChars <= 0 || id.length() <= keepTailChars) {
            return MASK;
        }
        return MASK + id.substring(id.length() - keepTailChars);
    }

    /** 保留尾部的位数，供结果里自述脱敏口径 */
    public int keepTailChars() {
        return keepTailChars;
    }

    /** 生效的 Bearer 规则条数（0 或 1）。供启动自检打印<span>真实</span>生效的规则规模 */
    public int bearerRuleCount() {
        return bearerPatterns.size();
    }

    /** 生效的关键字规则条数 */
    public int keywordRuleCount() {
        return secretValuePatterns.size();
    }

    /** 生效的自定义正则条数 */
    public int customRuleCount() {
        return customPatterns.size();
    }

    /**
     * 编译失败的正则清单。
     * <p><b>调用方必须把它带进结果里</b>：一条被静默跳过的脱敏规则与没有这条规则等价，
     * 而使用者以为自己被保护着。这是本类里唯一「必须被传播」的状态。
     */
    public List<String> patternWarnings() {
        return List.copyOf(patternWarnings);
    }

    /** 是否真的脱掉了东西。用于在结果上标注 {@code masked=true/false}，而不是无差别标注 */
    public boolean changed(String before, String after) {
        return before != null && !before.equals(after);
    }
}
