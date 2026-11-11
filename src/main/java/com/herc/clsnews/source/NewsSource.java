package com.herc.clsnews.source;

import com.herc.clsnews.model.RawItem;

import java.util.List;

/**
 * 来源适配层。财联社没有正式 API，接口路径和参数随时会变，
 * 所以把它隔离在这一个接口后面：换实现时下游（入库、接口、页面）一行不改。
 *
 * 现成的两个实现：
 *   ClsApiSource  —— 打 /v1/roll/get_roll_list，带 sign 签名
 *   MockSource    —— 假数据，断网时用来验证界面
 */
public interface NewsSource {

    /** 取最新一批。允许抛异常，调度器会记下来但不中断后续轮次。 */
    List<RawItem> fetch() throws Exception;

    /** 用于状态展示，例如 "cls.cn /v1/roll/get_roll_list" */
    String name();
}
