package com.herc.clsnews.agent;

import java.util.List;
import java.util.Locale;

/**
 * 一个内置分析模板，对应 {@code prompts/agent/templates.md} 里的一个 {@code ## <id>} 段。
 *
 * @param id       稳定标识，会写进 {@code agent_run.template_id}，改名等于换模板（缓存全失效）
 * @param title    浮层上显示的标题
 * @param brief    能否作为浮层首屏、能否参与水位线与增量判定。false 的模板每次都是 full
 * @param window   时间窗，{@code 1d} / {@code 7d} / {@code 12h}
 * @param keywords 检索关键词（OR），空表示不限
 * @param minHits  命中不足这个数就直接拒答，不调模型
 * @param topic    主题锚点词（OR）。候选里含有任一锚点的条数不够 {@code minTopicHits} 就拒答，
 *                 空表示不做主题校验。见 {@link Guard}
 * @param minTopicHits 主题锚点命中的下限；{@code topic} 非空且没写这一项时默认等于 {@code minHits}
 * @param expandTo 命中不足时允许扩到的时间窗。<b>S1.2 只解析不生效</b>，见 {@link Guard}
 * @param costHint 给界面看的成本量级，避免在代码里硬编码文案
 * @param system   system 提示词
 * @param user     user 提示词，含 {@code {{from}}/{{to}}/{{digest}}/{{news}}/{{count}}} 占位符
 */
public record AgentTemplate(
        String id,
        String title,
        boolean brief,
        String window,
        List<String> keywords,
        int minHits,
        List<String> topic,
        int minTopicHits,
        String expandTo,
        String costHint,
        String system,
        String user) {

    public AgentTemplate {
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        topic = topic == null ? List.of() : List.copyOf(topic);
        // 只写了 topic 没写下限：按 min-hits 走。不默认成 1 是因为 1 条命中
        // 挡不住今天这种"169 条里只有 1 条真属于 A 股"的情况
        if (!topic.isEmpty() && minTopicHits <= 0) {
            minTopicHits = minHits;
        }
    }

    /**
     * 时间窗秒数。
     *
     * <p>只支持 {@code d} 和 {@code h} 两种单位。不引 {@code java.time.Duration.parse} 是因为
     * 它要求 {@code PT24H} 这种 ISO 写法，写在 md 元数据里没人愿意读。
     */
    public long windowSeconds() {
        String w = window == null ? "" : window.trim().toLowerCase(Locale.ROOT);
        if (w.length() < 2) {
            throw new IllegalArgumentException("window 格式非法：" + window + "（应为 1d / 12h）");
        }
        char unit = w.charAt(w.length() - 1);
        int n;
        try {
            n = Integer.parseInt(w.substring(0, w.length() - 1));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("window 格式非法：" + window + "（应为 1d / 12h）", e);
        }
        if (n <= 0) {
            throw new IllegalArgumentException("window 必须为正：" + window);
        }
        return switch (unit) {
            case 'd' -> n * 86_400L;
            case 'h' -> n * 3_600L;
            default -> throw new IllegalArgumentException("window 单位只支持 d 或 h：" + window);
        };
    }

    /** 能否参与缓存与增量。名字比 {@code brief} 更能说明它影响什么。 */
    public boolean cacheable() {
        return brief;
    }
}
