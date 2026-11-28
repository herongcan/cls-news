package com.herc.clsnews.agent;

/**
 * 一次生成里的一个<b>确定性</b>步骤，给浮层的「过程」折叠区用。
 *
 * <p><b>为什么不记模型的思考过程</b>：模型的自述不可验证 —— 它可以说「我对比了三天」
 * 而实际只看了今天，也能把真正的推理过程说得面目全非。P2 要求结论可核对，
 * 把不可核对的东西摆在可核对的引用旁边，会把后者的可信度一起拉低。
 *
 * <p>这里记的是代码算出来的数字：取了多少条、剔了多少、打包多少、引用校验剔掉几个。
 * 主线突然变少时，用户的第一个问题是「是模型变差了，还是数据变了」，
 * 这串数字是唯一能回答后者的东西。
 *
 * @param k     机器码（scan / filter / merge / pack / guard / llm / cite），前端据此决定分组
 * @param label 中文短标签，控制在 6 字内（浮层一行要放下）
 * @param value 主值，以数字为主，控制在 10 字内
 * @param hint  补充说明，可为 null
 */
public record Step(String k, String label, String value, String hint) {

    public static Step of(String k, String label, String value) {
        return new Step(k, label, value, null);
    }
}
