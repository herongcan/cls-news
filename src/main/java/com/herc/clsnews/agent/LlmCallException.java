package com.herc.clsnews.agent;

/**
 * 调用失败、但<b>token 已经花出去</b>的异常。
 *
 * <p>存在的唯一理由：账要准。模型返回了内容却不能用（被 {@code max_tokens} 截断、
 * 返回空正文），这时 HTTP 是 200、token 已经计费，但异常路径原本会把用量丢掉，
 * 记成 {@code tokens_in=0}。结果是"账单上有钱、库里看不到花了多少" ——
 * 排查成本异常时，这种账是最难对的一种。
 *
 * <p>所以凡是能拿到 {@code usage} 的失败，都必须用这个异常带上用量。
 * 拿不到 {@code usage} 的（网络超时、401），仍然退化成普通异常，记 0 —— 不编造。
 */
public class LlmCallException extends RuntimeException {

    private final int tokensIn;
    private final int tokensOut;

    public LlmCallException(String message, int tokensIn, int tokensOut) {
        super(message);
        this.tokensIn = tokensIn;
        this.tokensOut = tokensOut;
    }

    public int tokensIn() {
        return tokensIn;
    }

    public int tokensOut() {
        return tokensOut;
    }
}
