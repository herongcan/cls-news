package com.herc.clsnews.agent;

/**
 * 主线生成用的模型接口。刻意比 {@code Analyzer} 窄：只有一个方法、没有 News 入参。
 *
 * <p>为什么不复用 {@code Analyzer}：那个接口的语义是"给一条新闻判利好利空"，
 * 入参是 {@code News}、出参是 {@code AnalysisResult}（sentiment/confidence/stocks）。
 * 主线要的是完全不同的东西 —— 一批新闻进、带引用的几条结论出。
 * 硬塞进同一个接口，只会让两个都不好用。
 *
 * <p><b>这里没有 tools 参数，是有意的。</b>Step 1 的检索决策全部在 Java 里
 * （{@link Retrieval} → {@link Classifier} → {@link Scorer} → {@link Packer}），
 * 模型只是"读一堆已经筛好的材料然后总结"。等 Step 2 出现"需要模型自己决定查什么"
 * 的真实需求，再引 tool calling；在那之前给一个用不上的参数，只是给未来的自己挖坑。
 */
public interface AgentLlm {

    /**
     * 单次调用，一次请求出结果。
     *
     * @param system 系统提示词
     * @param user   用户提示词（含 {@code <news>} 段）
     * @return 模型原始输出 + 用量
     * @throws Exception 网络/HTTP/协议错误。是否重试由实现决定，调用方不重试 ——
     *                   否则重试次数会变成乘法（2×2=4 次），而每次都是真金白银
     */
    Reply chat(String system, String user) throws Exception;

    /** key 配好了才为 true。false 时调用方应直接回 503，而不是逐次去撞墙。 */
    boolean ready();

    String modelName();

    /**
     * @param content  模型返回的正文（可能被 ```json 包裹，由解析器处理）
     * @param tokensIn  prompt token
     * @param tokensOut completion token
     * @param model     模型自报的名字，用于核对线上到底跑的哪个
     * @param latencyMs 本次耗时，写进 agent_step 排查首字节问题
     */
    record Reply(String content, int tokensIn, int tokensOut, String model, long latencyMs) {
    }
}
