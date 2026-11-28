package com.herc.clsnews.agent;

import java.util.ArrayList;
import java.util.List;

/**
 * 主线生成的输出契约，见设计文档 §6.2。
 *
 * @param title        标题，模型没给就用模板的
 * @param bullets      结论，每条 1–4 个引用；<b>列表里不会出现空引用条目</b>
 * @param insufficient 模型自报"材料不足"。它只是提示，真正决定成败的是 bullets 是否为空
 */
public record Answer(String title, List<Bullet> bullets, boolean insufficient) {

    public Answer {
        bullets = bullets == null ? List.of() : List.copyOf(bullets);
    }

    /**
     * @param text 一句话结论
     * @param refs 新闻 id，已校验为候选集内的真实条目
     */
    public record Bullet(String text, List<Long> refs) {
        public Bullet {
            refs = refs == null ? List.of() : List.copyOf(refs);
        }
    }

    /** 全部引用去重后的集合，用于写 {@code agent_run.citations_json}。 */
    public List<Long> allRefs() {
        List<Long> out = new ArrayList<>();
        for (Bullet b : bullets) {
            for (Long r : b.refs()) {
                if (!out.contains(r)) {
                    out.add(r);
                }
            }
        }
        return out;
    }

    public boolean empty() {
        return bullets.isEmpty();
    }
}
