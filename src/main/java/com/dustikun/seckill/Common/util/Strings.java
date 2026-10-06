package com.dustikun.seckill.Common.util;

/**
 * 字符串小工具。
 *
 * <p>本类刻意做成静态工具方法集合，不依赖任何框架，便于单测与跨层复用。
 */
public final class Strings {

    private Strings() {
    }

    /**
     * 截断字符串到指定长度，用于写入有长度上限的数据库列（如 {@code varchar(255)}）。
     *
     * <p>语义约定：
     * <ul>
     *   <li>{@code text == null} → 返回 {@code null}（不把 NULL 变成空串 —— 写进可空列时
     *       NULL 比 '' 更诚实，下游能区分「没有错误信息」与「错误信息为空」）。</li>
     *   <li>{@code max <= 0} → 返回空串（防御非法上界，避免 {@code substring(0, 负数)} 抛异常）。</li>
     *   <li>长度未超限 → 原样返回，不做拷贝。</li>
     * </ul>
     *
     * @param text 原字符串，可为 null
     * @param max  允许的最大长度
     * @return 截断后的字符串；null 入参返回 null
     */
    public static String truncate(String text, int max) {
        if (text == null) {
            return null;
        }
        if (max <= 0) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max);
    }
}
