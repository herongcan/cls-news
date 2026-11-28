package com.herc.clsnews.agent;

/**
 * 主线生成的可分类异常。与 {@code AnalysisException} 同构：状态码 + 机器码 + 人话，
 * 因为前端要按 code 决定"重试 / 去改配置 / 今天别点了"。
 *
 * <p>不复用 {@code AnalysisException} 是因为两者的错误码集合不同：
 * 这边多出 {@code NO_TEMPLATE}、{@code NO_TEMPLATES}、{@code REJECTED}，
 * 凑在一个枚举里会让前端的 switch 两边的分支都长。
 */
public class AgentException extends RuntimeException {

    private final int status;
    private final String code;

    public AgentException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public int status() {
        return status;
    }

    public String code() {
        return code;
    }
}
