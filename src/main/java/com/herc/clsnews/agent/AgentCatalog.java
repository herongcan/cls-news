package com.herc.clsnews.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/**
 * 模板目录的运行时持有者，负责"加载失败也不许拖垮进程"。
 *
 * <p>{@link AgentTemplates#parse} 校验很严：缺 title、window 写错、少一段 system 都会抛。
 * 这是有意的 —— 模棱两可地跑起来，用户看到的是"生成失败"，而原因藏在日志里。
 * 但严格校验直接放进 Bean 的构造函数，会让<b>一个错别字导致整个应用起不来</b>：
 * 这个工具的采集才是本体，分析只是增强，不能因为提示词文件写错就连新闻都不采了。
 *
 * <p>所以这里把异常收住：记一条 error 日志，目录为空，{@code /api/agent/*} 返回 503 并带上原因，
 * 其余功能照常。和 {@code DeepSeekAnalyzer} 缺 key 时的取舍保持一致。
 */
@Component
public class AgentCatalog {

    private static final Logger log = LoggerFactory.getLogger(AgentCatalog.class);

    private static final String RESOURCE = "prompts/agent/templates.md";

    private final AgentTemplates templates;
    private final String loadError;

    public AgentCatalog() {
        AgentTemplates loaded = null;
        String err = null;
        try {
            loaded = AgentTemplates.load(RESOURCE);
            log.info("分析模板已加载：{} 个（{}）", loaded.size(),
                    loaded.all().stream().map(AgentTemplate::id).toList());
        } catch (Exception e) {
            err = e.getMessage();
            log.error("分析模板加载失败，/api/agent/* 将返回 503（采集与分析单条不受影响）：{}", err);
        }
        this.templates = loaded;
        this.loadError = err;
    }

    /** 目录可用（文件存在且校验通过）。 */
    public boolean ready() {
        return templates != null;
    }

    /** 加载失败的原因，给 503 的响应体用；正常时为 null。 */
    public String loadError() {
        return loadError;
    }

    public Optional<AgentTemplate> find(String id) {
        return templates == null ? Optional.empty() : templates.find(id);
    }

    public List<AgentTemplate> all() {
        return templates == null ? List.of() : templates.all();
    }
}
