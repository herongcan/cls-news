package com.herc.clsnews.web;

/**
 * 把用户输入编成 SQL LIKE 模式，同时转义 LIKE 自己的通配符。
 *
 * <p>单独成一个类只为了能被测试直接调用 —— 这段逻辑有两种错法，而且都不会报错：
 * 漏转义会让搜索「瞎匹配」（搜 {@code 100%} 变成「100 后面随便什么」，
 * 搜 {@code a_b} 命中 {@code axb}），转义顺序错会把转义符本身吃掉
 * （先转 {@code %} 再转 {@code \}，新插进去的反斜杠会被二次转义）。
 * 两种情况都只能靠「搜出来是什么」来发现，所以它必须能被断言。
 *
 * <p>不涉及注入：拼出来的始终是绑定参数的值，不是 SQL 片段。
 */
public final class LikePattern {

    private LikePattern() {
    }

    /**
     * @param raw 用户原始输入（可为 null，按空串处理）
     * @return 形如 {@code %词%} 的模式，内部通配符已转义，配合 {@code ESCAPE '\'} 使用
     */
    public static String of(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "%%";
        }
        // 顺序不能反：反斜杠必须先转，否则后面新插入的转义符会被二次转义
        String s = raw.replace("\\", "\\\\")
                .replace("%", "\\%")
                .replace("_", "\\_");
        return "%" + s + "%";
    }
}
