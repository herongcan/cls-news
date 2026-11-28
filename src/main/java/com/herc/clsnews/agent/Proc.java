package com.herc.clsnews.agent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 一次生成的过程记录器：一边攒成列表（落库 / 随结果返回），一边往外推（SSE）。
 *
 * <p><b>为什么不是"返回 List 然后自己推"</b>：步骤散在 {@link AgentService#generate} 的
 * 十几个分支里，每个分支各自 add 一次。若推送要由调用方补，就得在每处再写一行
 * 「如果有人在听，把刚才那行推出去」—— 漏一处就是界面上少一行，而且漏了不会报错。
 * 收在一个对象里，加步骤的人只写 {@code proc.add(...)}，推不推由构造时给的口决定。
 *
 * <p><b>推送失败绝不打断生成。</b>客户端关掉页面时 {@code SseEmitter.send} 会抛，
 * 而那一刻模型可能已经算到一半、钱已经花出去了。让一个断开的连接把这次生成带崩，
 * 结果是「付了钱、什么都没存下来、下次还得再付一次」。所以 sink 的异常在这里一律吞掉，
 * 由传输层自己标记连接已断。
 *
 * <p><b>同步路径就传 {@link #NOOP}</b>：{@code /api/agent/ask} 仍然是"等模型出结果再一次性返回"，
 * 它要的只是这份列表，不需要推。
 */
public final class Proc {

    /** 往外推的口。传输层实现它（SSE emitter / 什么也不做）。 */
    public interface Sink {
        void step(Step s);

        /**
         * 推一段思考文本。
         *
         * <p>与 {@link #step} 的区别：step 是「一步完成后的快照」，reasoning 是「进行中的增量」——
         * 模型边想边吐，每吐一段就回调一次。默认空实现，同步路径（{@code NOOP}）和
         * 不关心思考的传输层都不用改。
         */
        default void reasoning(String chunk) {
        }
    }

    /** 同步路径用：只攒列表，不推。 */
    public static final Sink NOOP = new Sink() {
        @Override
        public void step(Step s) {
        }
    };

    private final List<Step> steps = new ArrayList<>(8);
    private final Sink sink;

    public Proc(Sink sink) {
        this.sink = sink == null ? NOOP : sink;
    }

    /** 追加一行。同 k 的旧行不动 —— 要覆盖请用 {@link #replace}。 */
    public Proc add(Step s) {
        steps.add(s);
        push(s);
        return this;
    }

    public Proc addAll(List<Step> more) {
        for (Step s : more) {
            add(s);
        }
        return this;
    }

    /**
     * 覆盖最后一个同 k 的行，没有则追加。
     *
     * <p>只有一个用处：模型那步先占一行「等待中」，回来后要原地变成「9.9 秒」。
     * 若改成追加，落库的列表里会留下两行同 k 的记录，读日志的人得自己判断哪行是真的。
     *
     * <p>前端也是按 k 覆盖的，两边的语义在这里对齐；换成一个新 k 反而会让界面多出一行。
     */
    public Proc replace(Step s) {
        for (int i = steps.size() - 1; i >= 0; i--) {
            if (steps.get(i).k().equals(s.k())) {
                steps.set(i, s);
                push(s);
                return this;
            }
        }
        return add(s);
    }

    /** 落库与返回值用这份。不可变，避免调用方误改已经推出去的东西。 */
    public List<Step> list() {
        return Collections.unmodifiableList(steps);
    }

    /**
     * 清掉已经攒的行，换一批重推。
     *
     * <p>只有一个用处：缓存命中时，scan 阶段已经把「取数/剔除/打包」推了出去，
     * 但那几行的数字是「这一次」扫出来的，而命中的是「上一次」的结论 —— 两份可能对不上。
     * 与其让界面出现"取数 75 条 + 调用模型 28.5 秒"这种跨次拼接，不如整段换成
     * 上次那份完整过程，和 note 里那句「数据没有变化，直接返回上一版」正好自洽。
     *
     * @param replacement 要重推的完整过程（通常来自上次落库的 steps_json）
     */
    public Proc reset(List<Step> replacement) {
        steps.clear();
        return addAll(replacement);
    }

    public int size() {
        return steps.size();
    }

    /**
     * 把一段思考增量透传给 sink。
     *
     * <p>思考文本<b>不进 steps 列表</b>：列表里每行是一个已完成步骤，而思考是流式的、
     * 会被覆盖几十次的中间态。它由调用方攒全后，再合成一个 {@code k="reasoning"} 的
     * {@link Step} 落库（见 {@link Steps#reasoning}）。这里只负责「实时透传」这一半。
     */
    public void reasoning(String chunk) {
        try {
            sink.reasoning(chunk);
        } catch (Exception ignored) {
            // 同 step 的吞异常：断线是传输层的事，不连带这次生成废掉
        }
    }

    private void push(Step s) {
        try {
            sink.step(s);
        } catch (Exception ignored) {
            // 见类注释：连接断了是传输层的事，不能连带把这次生成废掉
        }
    }
}
