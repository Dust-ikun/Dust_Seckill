package com.dustikun.seckill.monitor.core;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 脱敏规则回归（SPEC 第 17.2 节）。
 *
 * <p><b>为什么这个测试比它看起来更重要</b>：脱敏是本项目里唯一一类
 * 「配错了也不会有任何症状」的功能 —— 应用照常启动、日志照常输出、
 * Tool 照常返回结果，唯一的区别是某个口令被送进了 LLM 的上下文。
 * 没有测试时，这类缺陷只会在一次安全复查或一次事故之后才被发现。
 *
 * <p>因此这里的用例不是「随便测几个正则」，而是逐条锁住 SPEC 第 17.2 节
 * 列的六类禁止信息里<b>最可能出现在日志中的那几条</b>，
 * 并且额外锁住反向约束：<b>不该脱的不能被脱掉</b> ——
 * 脱敏越界会让排障时看不出连的是哪个库、走的哪条链路，那同样是一种缺陷。
 */
@DisplayName("脱敏：进入 LLM 的文本不得包含敏感信息")
class MaskerTest {

    /** 用默认规则（application-docker.yaml 里的那份等价物）构造 */
    private static Masker defaultMasker() {
        return new Masker(new MaskProperties(null, null, null));
    }

    @Test
    @DisplayName("JDBC 连接串口令被脱敏，且其它参数保留")
    void jdbcPasswordIsMaskedButOtherParamsSurvive() {
        Masker masker = defaultMasker();
        String raw = "连接失败 url=jdbc:mysql://localhost:3306/seckill"
                + "?user=root&password=example-value-a1b2c3&useSSL=false&serverTimezone=Asia/Shanghai";

        String masked = masker.mask(raw);

        assertFalse(masked.contains("example-value-a1b2c3"), "口令绝不能出现在输出里，实际：" + masked);
        assertTrue(masked.contains("password="), "`password=` 这个上下文要保留，让人看得出被脱敏的字段");
        // 【反向断言】脱敏越界同样是缺陷：若把 useSSL / serverTimezone 一起吃掉了，
        // 排障时就再也看不出「是不是连错了环境」——而那往往是根因本身。
        assertTrue(masked.contains("useSSL=false"), "连接参数不该被一并抹掉，实际：" + masked);
        assertTrue(masked.contains("serverTimezone=Asia/Shanghai"), "连接参数不该被一并抹掉");
        assertTrue(masked.contains("localhost:3306/seckill"), "主机与库名要保留");
    }

    @Test
    @DisplayName("各种写法的口令/token 都被脱敏：key=value、JSON、Bearer")
    void allSecretShapesAreMasked() {
        Masker masker = defaultMasker();

        // 每项是「原文」+「绝不能出现在输出里的那一段」
        String[][] cases = {
                {"password=abc123", "abc123"},
                {"PASSWORD: abc123", "abc123"},
                {"\"password\": \"abc123\"", "abc123"},
                {"pwd=abc123", "abc123"},
                {"api-key=sk-live-abc123", "abc123"},
                // 【Bearer 是独立规则】关键字规则只会吃掉 "Bearer" 这个词，
                // 真正的密钥在后面。只靠 keyword 列表会让它原样漏出去 ——
                // 而输出里有个 *** 会让人以为已经脱敏了。
                {"Authorization: Bearer sk-abc123def456", "abc123def456"},
                {"Authorization: Bearer eyJhbGciOiJIUzI1NiJ9.payload.sig", "eyJhbGciOiJIUzI1NiJ9"},
                {"token=eyJhbGciOiJIUzI1NiJ9.payload.sig", "eyJhbGciOiJIUzI1NiJ9"},
                {"cookie=JSESSIONID=ABC123XYZ", "ABC123XYZ"},
        };
        for (String[] c : cases) {
            String masked = masker.mask(c[0]);
            assertFalse(masked.contains(c[1]),
                    "敏感值未被脱敏：" + c[0] + " → " + masked);
            assertTrue(masked.contains(Masker.MASK), "必须留下占位符，实际：" + masked);
        }
    }

    @Test
    @DisplayName("字段名必须保留：password=*** 而不是光秃秃的 ***")
    void fieldNameSurvivesMasking() {
        Masker masker = defaultMasker();

        // 【为什么这条单独测】只断言「敏感值不见了」是不够的 —— 一个把整段都换成 ***
        // 的实现同样能通过那个断言。而输出只剩 *** 时，读日志的人无法判断
        // 这里原本是口令、是 token、还是整行都被日志系统弄坏了。
        // 脱敏的目标是「去掉值、留住结构」，两者都要能被测出来。
        String masked = masker.mask("连接失败 password=example-value-a1b2c3 host=db1");
        assertFalse(masked.contains("example-value-a1b2c3"));
        assertTrue(masked.contains("password=***"),
                "字段名与分隔符要保留，实际：" + masked);
        assertTrue(masked.contains("host=db1"), "无关内容一个字都不该动，实际：" + masked);

        // Bearer 是另一种形态：锚点词也要留下
        String bearer = masker.mask("Authorization: Bearer sk-abcdef123456");
        assertFalse(bearer.contains("sk-abcdef123456"), "实际：" + bearer);
        assertTrue(bearer.contains("Bearer ***") || bearer.contains("*** ***"),
                "至少要能看出这里原本是一个 Bearer 凭证，实际：" + bearer);
    }

    @Test
    @DisplayName("手机号 / 身份证 / 银行卡被脱敏")
    void personalIdentifiersAreMasked() {
        Masker masker = defaultMasker();

        assertFalse(masker.mask("phone=13800138000").contains("13800138000"));
        assertFalse(masker.mask("idCard=11010119900307123X").contains("11010119900307123X"));
        assertFalse(masker.mask("card=6222021234567890123").contains("6222021234567890123"));
    }

    @Test
    @DisplayName("正常日志不被改动：脱敏只碰它认得的东西")
    void ordinaryTextIsUntouched() {
        Masker masker = defaultMasker();

        String[] safe = {
                "[消费·确认成功] orderNo=SN1759900000123456, userId=1001, stockId=1, 投递到确认耗时=12ms",
                "[Outbox·投递] 本轮投出 500 条，耗时 18 ms，仍待投递 0 条",
                "[对账] stockId=1 一致。Redis=999, DB=999, 可信在途=0",
                "java.net.SocketTimeoutException: Read timed out",
        };
        for (String text : safe) {
            assertEquals(text, masker.mask(text), "普通日志被改动了：" + text);
        }
    }

    @Test
    @DisplayName("标识符保留末尾若干位：既能对上号，又不全量外发")
    void identifiersKeepTail() {
        Masker masker = defaultMasker();

        assertEquals("***3456", masker.maskIdentifier("SN1759900000123456"));
        assertEquals("***6789", masker.maskIdentifier("123456789"));
        // 太短时整体替换：在 4 位数字上「保留尾 4 位」等于全部泄露
        assertEquals(Masker.MASK, masker.maskIdentifier("123"));
        assertEquals(Masker.MASK, masker.maskIdentifier("1234"));
        assertEquals(null, masker.maskIdentifier(null));
        assertEquals("", masker.maskIdentifier(""));
    }

    @Test
    @DisplayName("同一输入两次脱敏结果一致：traceId 脱敏后仍然可以用于跨查询关联")
    void maskingIsDeterministic() {
        Masker masker = defaultMasker();
        String traceId = "a1b2c3d4e5f60718";

        String first = masker.maskIdentifier(traceId);
        String second = masker.maskIdentifier(traceId);

        // 【这一条是 Logs Tool 能工作的前提】Agent 可能先用一次查询拿到 traceId，
        // 再用它做第二次查询（"把这一单的全链日志取出来"）。
        // 两次脱敏结果若不同，这条链路就断了，而 Agent 只会看到「查不到」。
        assertEquals(first, second, "脱敏必须是确定性的");
        assertFalse(first.contains(traceId), "但原值不能保留");
        // 不同的 traceId 仍然可区分（否则所有调用链会被合并成一条）
        assertFalse(first.equals(masker.maskIdentifier("ffffffffffffffff")),
                "不同的 traceId 脱敏后仍应可区分");
    }

    @Test
    @DisplayName("已脱敏的内容不会被二次脱敏（重放结果稳定）")
    void alreadyMaskedTextIsStable() {
        Masker masker = defaultMasker();

        String once = masker.mask("password=abc123");
        String twice = masker.mask(once);

        assertEquals(once, twice, "重复脱敏不该继续变形");
    }

    @Test
    @DisplayName("非法正则被记录成告警而不是静默跳过")
    void brokenPatternIsReportedNotSilentlySkipped() {
        // 一个真实会犯的错：分组没闭合。
        // 【注意不能用 (password=)(?i)([^&]+) 当反例】那种写法在 Java 里是<b>合法</b>的
        // （内联旗标不在开头也不会报错），用它做反例会得到一个「测试通过但没测到东西」的假象。
        Masker masker = new Masker(new MaskProperties(
                4, List.of("password"), List.of("(password=)([^&]+")));

        assertFalse(masker.patternWarnings().isEmpty(),
                "编译失败必须留下痕迹 —— 一条被静默跳过的脱敏规则与没有这条规则等价");
        assertTrue(masker.patternWarnings().get(0).contains("patterns[0]"),
                "告警要指出是哪一条，实际：" + masker.patternWarnings());
        assertTrue(masker.patternWarnings().get(0).contains("Unclosed"),
                "告警要带上有用的原因，实际：" + masker.patternWarnings());
        // 而且关键字规则仍然生效，不是整台机器停摆
        assertFalse(masker.mask("password=abc123").contains("abc123"));
    }

    @Test
    @DisplayName("自定义正则：替换的是「第 1 组」，组前后的内容都保留")
    void groupSemanticsDecideReplacementScope() {
        // 【必须显式给一个不会命中的关键字】空列表会被 normalized() 换成默认关键字表，
        // 其中就有 password —— 那样一来关键字规则会先把值脱掉，
        // 这条用例测到的就不再是「自定义正则的分组语义」了。
        // 这类「测试看似通过、实际测的不是它声称的东西」是最难发现的一类问题。
        List<String> noKeyword = List.of("zzz-never-matches");

        // 第 1 组是 `password=` 这一个片段（正好 9 个字符），只有它被替换，
        // 它之后的 `abc123` 原样保留。
        // 【这条用例是三次下标写错的直接产物】三种错法都不报错，
        // 只是输出从 `***abc123` 悄悄变成 `password=***` 或 `******`。
        Masker whole = new Masker(new MaskProperties(4, noKeyword,
                List.of("(password=)([^&;,\\s]+)")));
        assertEquals("***abc123",
                whole.mask("password=abc123"),
                "第 1 组覆盖 `password=`，只有它被替换；后面的 `abc123` 必须原样保留");

        // 想「只脱值、保留字段名」时，把值放进第 1 组
        Masker valueOnly = new Masker(new MaskProperties(4, noKeyword,
                List.of("password=(\\S+)")));
        assertEquals("password=***", valueOnly.mask("password=abc123"));

        // 无分组时整体替换。适合「整段都敏感」的场景（如一份完整连接串）
        Masker noGroup = new Masker(new MaskProperties(4, noKeyword,
                List.of("jdbc:mysql://\\S+")));
        assertEquals("***", noGroup.mask("jdbc:mysql://localhost:3306/seckill"));
    }

    @Test
    @DisplayName("三段式拼接：匹配区间里、第 1 组之外的内容不会被吞掉")
    void textOutsideGroupInsideMatchSurvives() {
        // 【这条锁的是一个「不报错但输出更短」的缺陷】
        // 第一版只拼「组之前 + 占位符」就跳到 m.end()，于是匹配区间里
        // 第 1 组之后的部分被整段丢弃：`card=...` 变成光秃秃的 `***`。
        // 同样要显式给一个不会命中的关键字，避免关键字规则先动手。
        Masker masker = new Masker(new MaskProperties(4, List.of("zzz-never-matches"),
                List.of("card=(\\d{18})")));
        assertEquals("card=***", masker.mask("card=622202123456789012"));

        // 同一行里有前后文时，两侧都不能丢
        assertEquals("前置 card=*** 后置",
                masker.mask("前置 card=622202123456789012 后置"));
    }

    @Test
    @DisplayName("null 与空串安全通过")
    void nullAndEmptyAreSafe() {
        Masker masker = defaultMasker();
        assertEquals(null, masker.mask(null));
        assertEquals("", masker.mask(""));
        assertNotNull(masker.patternWarnings());
    }

    @Test
    @DisplayName("默认规则在配置缺失时兜底存在：默认不脱敏是最危险的默认")
    void defaultsApplyWhenConfigIsEmpty() {
        // 模拟「application-docker.yaml 里的 mask 段被整体删掉」
        Masker masker = new Masker(new MaskProperties(null, List.of(), List.of()));

        assertTrue(masker.keepTailChars() > 0, "保留位数要有默认值");
        assertFalse(masker.mask("password=example-value-a1b2c3").contains("example-value-a1b2c3"),
                "配置缺失时也必须脱敏 —— 否则删掉一段 YAML 就会静默关闭合规约束");
        assertTrue(masker.patternWarnings().isEmpty(), "兜底规则本身必须是合法的");
    }
}
