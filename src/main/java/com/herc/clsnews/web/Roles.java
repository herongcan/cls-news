package com.herc.clsnews.web;

import com.herc.clsnews.config.AppProps;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 「当前请求是谁、额度怎么算」的唯一实现。
 *
 * <p>抽出来是因为它已经有两处调用方（{@link NewsController} 的单条分析、
 * {@link AgentController} 的主线生成），而这两处共用同一本账：游客的日额度是同一个概念，
 * 只是上限值不同。分开各写一份的话，哪天改了「游客额度按角色还是按账号」的口径，
 * 只会改到其中一处 —— 表现出来就是「分析提示已用完、主线还能点」。
 *
 * <p>三个方法都很短，短到"值不值得单独一个类"是个合理的问题。判断标准是**有没有第二个调用方**
 * 以及**改错的代价**：这里改错的代价是一次模型调用（1 万 token），不是一次样式错位。
 */
public final class Roles {

    private Roles() {
    }

    /**
     * 当前请求的角色。
     *
     * <p>没开鉴权时 {@link BasicAuthFilter} 这个 Bean 根本不存在，请求属性也就没人设 ——
     * 那种状态下按可写处理：本来就没人保护，报只读只会让页面莫名其妙藏掉按钮。
     */
    public static String of(HttpServletRequest req) {
        Object r = req.getAttribute(BasicAuthFilter.ROLE_ATTR);
        return r == null ? BasicAuthFilter.ROLE_RW : String.valueOf(r);
    }

    /** 游客有独立日额度；其余角色共用全局 daily-cap。null = 无独立上限。 */
    public static Integer capOf(String role, AppProps props) {
        return BasicAuthFilter.ROLE_GUEST.equals(role) ? props.getSecurity().getGuestDailyCap() : null;
    }

    /**
     * 查用量的口径：游客看自己那本账，其他人看全站。
     *
     * <p>全站的 daily-cap 是钱包保险丝，不是某个人的额度；给 rw 也按账号算的话，
     * 十几个人共用一个 admin 账号时那个数字等于没有意义。
     */
    public static String quotaActor(String role) {
        return BasicAuthFilter.ROLE_GUEST.equals(role) ? role : null;
    }
}
