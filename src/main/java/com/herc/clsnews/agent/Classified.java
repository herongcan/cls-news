package com.herc.clsnews.agent;

import com.herc.clsnews.ai.NewsClass;

/**
 * 分类（并合并）之后的候选条目。
 *
 * <p>它和 {@link NewsRow} 的区别只有两件事：带上了路由类别、带上了"这条代表了多少条"。
 *
 * @param row         原始行
 * @param kind        路由类别
 * @param mergedCount 合并了几条原始播报；1 表示没被合并
 */
public record Classified(
        NewsRow row,
        NewsClass.Kind kind,
        int mergedCount) {

    public static Classified of(NewsRow row, NewsClass.Kind kind) {
        return new Classified(row, kind, 1);
    }

    /** 是否被合并过（同一天对同一标的播了多次）。 */
    public boolean merged() {
        return mergedCount > 1;
    }

    /**
     * 进 prompt 时展示的标题。被合并过的会在末尾标注次数。
     *
     * <p>为什么要标出来而不是默默只留一条：单看"跌幅扩大至4%"看不出这是持续扩大的结果，
     * 加上"（当日 3 次播报）"这个趋势信号才是合并真正多出来的信息 —— 合并的收益不只是省 token。
     */
    public String displayTitle() {
        String t = row.title() == null ? "" : row.title();
        return merged() ? t + "（当日 " + mergedCount + " 次播报）" : t;
    }
}
