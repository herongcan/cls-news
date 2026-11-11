package com.herc.clsnews.ai;

/**
 * 分析失败的可分类异常。
 *
 * <p>为什么不用 RuntimeException + 字符串：这里的失败原因是**给用户看的**，
 * 而且不同原因对应完全不同的动作——
 *   DISABLED / NOT_READY → 去改配置，点多少次都没用；
 *   BUSY                 → 等 3 秒再点，不用管；
 *   CAPPED               → 今天别点了；
 *   MODEL_ERROR          → 可以重试。
 * 混成一个 500 之后，用户唯一能做的就是反复点，反复扣钱。
 */
public class AnalysisException extends RuntimeException {

    /** 建议回给前端的 HTTP 状态码 */
    private final int status;
    /** 机器可读的错误码，前端据此决定要不要自动重试 */
    private final String code;

    public AnalysisException(int status, String code, String message) {
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
