package com.herc.clsnews.agent;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * {@code agent_run} / {@code agent_step} 的读写。
 *
 * <p>这张表不只是日志，它同时承担三个职责，所以字段设计比较重：
 * <ul>
 *   <li><b>结果快照</b>：{@code answer_json} 让"生成完关掉页面再打开"能直接看，
 *       也让小程序端可以轮询同一个 runId。</li>
 *   <li><b>缓存凭证</b>：{@code (template_id, window_key, watermark, params_json)} 加上部分唯一索引，
 *       是"同一批数据只算一次"的唯一硬保证。</li>
 *   <li><b>生成权</b>：同键的 {@code running} 也只允许一行（见 {@link #claim}），
 *       第二个请求在 INSERT 那一刻就被挡住，还没调模型就已经省了钱。</li>
 *   <li><b>配额账本</b>：数 {@code status='succeeded'} 的行就是"今天生成成功几次"。
 *       不另建额度表的原因见 {@link #countSucceededSince}。</li>
 * </ul>
 *
 * <p><b>{@code trigger} 必须带双引号</b>：它是 SQLite 关键字。
 * 建表时作为列名能过（实测通过），但 INSERT 的列清单里不加引号会解析歧义。
 */
@Repository
public class AgentRunStore {

    /** 别名 {@code r}：{@link #ROW} 与各处 SQL 共用同一份列清单，加字段只改一处。 */
    private static final String COLUMNS = """
            r.id, r.template_id, r.window_key, r.strategy, r.base_run_id, r.watermark, r.status,
            r.answer_json, r.citations_json, r.steps_json, r.candidates, r.tokens_in,
            r.tokens_out, r.model, r.created_at
            """;

    private static final RowMapper<RunRow> ROW = (rs, i) -> new RunRow(
            rs.getString("id"),
            rs.getString("template_id"),
            rs.getString("window_key"),
            rs.getString("strategy"),
            rs.getString("base_run_id"),
            rs.getLong("watermark"),
            rs.getString("status"),
            rs.getString("answer_json"),
            rs.getString("citations_json"),
            rs.getString("steps_json"),
            rs.getInt("candidates"),
            rs.getInt("tokens_in"),
            rs.getInt("tokens_out"),
            rs.getString("model"),
            rs.getLong("created_at"));

    private final JdbcTemplate jdbc;

    public AgentRunStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        // 老库没有 steps_json（这一列是「过程」功能加的）。SQLite 的 ALTER TABLE
        // 没有 ADD COLUMN IF NOT EXISTS，重复执行会抛错，所以吞掉 —— 与 analysis_call
        // 补 actor 列是同一套做法。表还不存在时这里也会失败，随后 schema.sql 会带着新列建表。
        try {
            jdbc.execute("ALTER TABLE agent_run ADD COLUMN steps_json TEXT");
        } catch (Exception ignored) {
            // 已经有了
        }
    }

    /**
     * 补写「过程」。独立于终态写入，是为了不改动 {@code markSucceeded} /
     * {@code markFailed} 那几处已有的调用契约 —— 那些调用点有各自的 token 记账含义，
     * 为了多带一列去改签名，很容易在某次改动里把 token 传错。
     *
     * @param stepsJson null 时不覆盖：缓存命中的旧行没有过程，不应该被清成空
     */
    public void stampSteps(String runId, String stepsJson) {
        if (stepsJson == null || stepsJson.isBlank()) {
            return;
        }
        jdbc.update("UPDATE agent_run SET steps_json = ? WHERE id = ?", stepsJson, runId);
    }

    /**
     * 插入一条 running 行，<b>返回值是"有没有抢到"</b>。
     *
     * <p>为什么先插行再调模型：进程被杀、容器重启时，这行会留在 running 状态，
     * 下次启动能查出来（{@link #resetStuck}）。如果等成功才写，一次崩溃就等于
     * "既没有结果、也不知道发生过什么"，配额账上还查不到。
     *
     * <p>返回 false 说明 {@code uq_brief_active} 挡住了：该缓存键已经有一行在跑或已成功。
     * <b>这是唯一能"还没花钱就拦住"的地方</b> —— 应用层查缓存只能挡连点，
     * 挡不住两个请求同时越过检查。
     *
     * @return true = 抢到；false = 已有同键的 running / succeeded 行
     */
    public boolean insertRunning(String id, String templateId, String paramsJson, String triggerBy,
                                 String windowKey, String strategy, String baseRunId, long watermark) {
        try {
            jdbc.update("""
                    INSERT INTO agent_run
                        (id, kind, template_id, params_json, "trigger", trigger_by, window_key,
                         strategy, base_run_id, watermark, status, tokens_in, tokens_out, created_at)
                    VALUES (?, 'template', ?, ?, 'user', ?, ?, ?, ?, ?, 'running', 0, 0, ?)
                    """,
                    id, templateId, paramsJson, triggerBy, windowKey, strategy, baseRunId, watermark,
                    now());
            return true;
        } catch (DataAccessException e) {
            if (isUniqueViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    /**
     * 一步写入一条 rejected 记录（guard 拒答用）。
     *
     * <p>不走"先插 running 再改 rejected"，是因为 rejected 不在唯一索引的条件里 ——
     * 而 running 在。若此刻正好有别的请求在跑同一批数据，走 running 那条路会撞索引失败，
     * 把"材料不足"这个和钱毫无关系的结论变成一个 500。
     */
    public void insertRejected(String id, String templateId, String paramsJson, String triggerBy,
                               String windowKey, String strategy, long watermark,
                               String reason, int candidates) {
        long t = now();
        jdbc.update("""
                INSERT INTO agent_run
                    (id, kind, template_id, params_json, "trigger", trigger_by, window_key,
                     strategy, watermark, status, candidates, error, tokens_in, tokens_out,
                     created_at, finished_at)
                VALUES (?, 'template', ?, ?, 'user', ?, ?, ?, ?, 'rejected', ?, ?, 0, 0, ?, ?)
                """,
                id, templateId, paramsJson, triggerBy, windowKey, strategy, watermark,
                candidates, clip(reason, 500), t, t);
    }

    /**
     * 让同键那条已完成的记录退出"当前版本"，好让强制重算写出一条新的。
     *
     * <p>唯一索引覆盖 {@code running} 与 {@code succeeded}（见 schema 的 uq_brief_active），
     * 不先让位，新的 INSERT 会被判成"已经生成好了"，force 就变成了一句空话。
     *
     * <p><b>不删行</b>：那一版的钱确实花过，结论也真的存在过。删掉会让账单和库对不上，
     * 也会让"这一版为什么被替换"无据可查。superseded 只表示"它不再是当前版本"。
     *
     * @return 让位的行数（0 = 本来就没有同键记录）
     */
    int supersede(String templateId, String windowKey, long watermark, String paramsJson) {
        return jdbc.update("""
                UPDATE agent_run
                   SET status = 'superseded'
                 WHERE template_id = ? AND window_key = ? AND watermark = ?
                   AND IFNULL(params_json,'') = IFNULL(?,'')
                   AND status = 'succeeded' AND kind = 'template'
                """, templateId, windowKey, watermark, paramsJson);
    }

    /** 同键当前处于 running 或 succeeded 的那一行（最多一条，由唯一索引保证）。 */
    Optional<RunRow> findActive(String templateId, String windowKey, long watermark, String paramsJson) {        List<RunRow> rows = jdbc.query("""
                SELECT %s FROM agent_run r
                 WHERE r.template_id = ? AND r.window_key = ? AND r.watermark = ?
                   AND IFNULL(r.params_json,'') = IFNULL(?,'')
                   AND r.status IN ('running', 'succeeded') AND r.kind = 'template'
                 ORDER BY r.created_at DESC LIMIT 1
                """.formatted(COLUMNS), ROW, templateId, windowKey, watermark, paramsJson);
        return rows.stream().findFirst();
    }

    /**
     * 释放"僵尸占用"：把超时仍停在 running 的行标 failed。
     *
     * <p>{@link #resetStuck()} 只在启动时跑，挡不住运行期被 kill 留下的行。不清理的话，
     * 一次崩溃就把这个水位线永久锁死 —— 之后每次点击都只能 503，直到进程重启。
     *
     * @return 1 = 释放成功；0 = 别人已经处理（或它已经不是 running）
     */
    int markStaleFailed(String id, long before) {
        return jdbc.update("""
                UPDATE agent_run
                   SET status='failed', error='生成超时，占用已释放', finished_at=?
                 WHERE id=? AND status='running' AND created_at <= ?
                """, now(), id, before);
    }

    /**
     * 抢占一次生成权（方案 A 的入口）。
     *
     * <p>三种结果对应三种完全不同的后续动作：
     * <ul>
     *   <li>{@link Kind#CLAIMED} —— 抢到了，去调模型</li>
     *   <li>{@link Kind#DONE} —— 已经有人生成好了，直接返回那份结果（0 成本）</li>
     *   <li>{@link Kind#BUSY} —— 有人正在生成（多半是另一个进程），等一下再看</li>
     * </ul>
     *
     * @param staleBefore created_at 早于这个时间戳的 running 视为僵尸
     */
    public Claim claim(String id, String templateId, String paramsJson, String triggerBy,
                       String windowKey, long watermark, long staleBefore) {
        return claim(id, templateId, paramsJson, triggerBy, windowKey, "full", null, watermark, staleBefore);
    }

    /**
     * 同上，但允许指定策略与 base。
     *
     * <p>{@code strategy} 与 {@code base_run_id} 只影响"这次是什么"，不参与唯一索引 ——
     * 索引键是 {@code (template_id, window_key, watermark, params_json)}。
     * 这是有意的：同一天同一批数据，无论走 full 还是 delta，都只该产出一份结论。
     * 若把 strategy 也塞进索引，full 和 delta 就能同时成功，缓存里出现两份互相矛盾的"今日主线"。
     *
     * @param strategy  full / delta
     * @param baseRunId delta 所基于的 full；full 时传 null
     */
    public Claim claim(String id, String templateId, String paramsJson, String triggerBy,
                       String windowKey, String strategy, String baseRunId,
                       long watermark, long staleBefore) {
        if (insertRunning(id, templateId, paramsJson, triggerBy, windowKey, strategy, baseRunId, watermark)) {
            return new Claim(Kind.CLAIMED, null);
        }
        Optional<RunRow> active = findActive(templateId, windowKey, watermark, paramsJson);
        if (active.isEmpty()) {
            // 极窄的竞态：对方的行刚被标 failed，索引已释放但这次没读到。
            // 重试一次，比直接给用户一个 503 划算。
            return insertRunning(id, templateId, paramsJson, triggerBy, windowKey, strategy,
                    baseRunId, watermark)
                    ? new Claim(Kind.CLAIMED, null)
                    : new Claim(Kind.BUSY, null);
        }
        RunRow row = active.get();
        if ("succeeded".equals(row.status())) {
            return new Claim(Kind.DONE, row);
        }
        if (row.createdAt() > staleBefore) {
            return new Claim(Kind.BUSY, row);
        }
        if (markStaleFailed(row.id(), staleBefore) == 0) {
            return new Claim(Kind.BUSY, row);
        }
        return insertRunning(id, templateId, paramsJson, triggerBy, windowKey, strategy,
                baseRunId, watermark)
                ? new Claim(Kind.CLAIMED, null)
                : new Claim(Kind.BUSY, row);
    }

    /** {@link #claim} 的结果。 */
    public enum Kind {
        CLAIMED, DONE, BUSY
    }

    public record Claim(Kind kind, RunRow row) {
    }

    /**
     * 标成功。**返回值很关键**：false 表示撞了 {@code uq_brief_active}，
     * 说明同一批数据已经有别的请求先成功了 —— 这次的钱白花了，但结果不能覆盖。
     *
     * <p>有了 {@link #claim} 之后，这条兜底理论上很少触发：抢不到就不该调模型。
     * 保留它是因为 claim 与模型调用之间还有十几秒，这期间可能有第二个进程
     * 用同一把 key 抢跑了（它的 running 行与这次的 running 行互斥，本该抢不到，
     * 但如果它抢在本次释放之前完成，仍然会出现两行 succeeded 的竞争）。
     * 一句话：这是最后一道闸，不是第一道。
     *
     * @return true = 落库成功；false = 同键已有成功记录
     */
    public boolean markSucceeded(String id, String answerJson, String citationsJson, int candidates,
                                 int tokensIn, int tokensOut, String model, long latencyMs) {
        try {
            jdbc.update("""
                    UPDATE agent_run
                       SET status='succeeded', answer_json=?, citations_json=?, candidates=?,
                           tokens_in=?, tokens_out=?, model=?, latency_ms=?, finished_at=?
                     WHERE id=?
                    """, answerJson, citationsJson, candidates, tokensIn, tokensOut, model,
                    latencyMs, now(), id);
            return true;
        } catch (DataAccessException e) {
            // 部分唯一索引只在 succeeded 时生效，所以冲突只会发生在这个 UPDATE。
            //
            // 这里 catch 的是最宽的数据访问异常，不是 DuplicateKeyException，也不是
            // DataIntegrityViolationException —— SQLite 驱动报唯一约束时不带 SQLState，
            // Spring 的翻译器认不出来，实测最后抛的是 UncategorizedSQLException（同样是
            // DataAccessException 的子类）。所以按异常链里的约束名做二次确认：
            // 只有确实是唯一约束才当"并发重复"处理，其它数据库错误照旧抛出去。
            if (isUniqueViolation(e)) {
                return false;
            }
            throw e;
        }
    }

    /** SQLite 的唯一约束消息形如 {@code UNIQUE constraint failed: agent_run.template_id}。 */
    private static boolean isUniqueViolation(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = t.getMessage();
            if (m != null && (m.contains("UNIQUE constraint failed") || m.contains("uq_brief_active"))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 标失败。{@code tokens_in/out} 如实记 —— 失败也经常已经把 token 花出去了
     * （超时、截断、JSON 不合法都发生在模型返回之后）。
     */
    public void markFailed(String id, String error, int tokensIn, int tokensOut, long latencyMs) {
        jdbc.update("""
                UPDATE agent_run
                   SET status='failed', error=?, tokens_in=?, tokens_out=?, latency_ms=?, finished_at=?
                 WHERE id=?
                """, clip(error, 500), tokensIn, tokensOut, latencyMs, now(), id);
    }

    /** 标拒答（guard 判定材料不足）。token 恒为 0：一步都没调模型。 */
    public void markRejected(String id, String reason, int candidates) {
        jdbc.update("""
                UPDATE agent_run
                   SET status='rejected', error=?, candidates=?, tokens_in=0, tokens_out=0, finished_at=?
                 WHERE id=?
                """, clip(reason, 500), candidates, now(), id);
    }

    /**
     * 查同一缓存键是否已有成功结果（§10.3：template_id + window_key + watermark + params）。
     *
     * <p><b>不区分 strategy</b>：full 和 delta 的输出形态完全一样（都是完整的 3–5 条主线），
     * 缓存语义上等价。只认 full 会漏掉这种情况 —— 10 点跑了一次 delta 把水位线推到 1312，
     * 11 点再点，水位线仍是 1312，但 base full 的 watermark 还是 1300，判定于是
     * 继续往下走又跑一次 delta：数据一个字没变，用户却又被扣一次费。
     * 判定顺序见 §10.2：先做跨策略比对，再去找 base。
     *
     * <p><b>只认 {@code kind='template'}</b>：Step 2 的自由提问（{@code research}）
     * 不进这套缓存 —— 同一个问题的答案不该因为"数据库没变"就永远冻结。
     */
    public Optional<RunRow> findSucceeded(String templateId, String windowKey, long watermark,
                                          String paramsJson) {
        List<RunRow> rows = jdbc.query("""
                SELECT %s FROM agent_run r
                 WHERE r.template_id = ? AND r.window_key = ? AND r.watermark = ?
                   AND r.params_json = ? AND r.status = 'succeeded'
                   AND r.kind = 'template'
                 ORDER BY r.created_at DESC LIMIT 1
                """.formatted(COLUMNS), ROW, templateId, windowKey, watermark, paramsJson);
        return rows.stream().findFirst();
    }

    public Optional<RunRow> findById(String id) {
        List<RunRow> rows = jdbc.query("""
                SELECT %s FROM agent_run r WHERE r.id = ?
                """.formatted(COLUMNS), ROW, id);
        return rows.stream().findFirst();
    }

    /**
     * 查当日最近一次成功的 <b>full</b>，作为增量的 base。
     *
     * <p><b>不能复用 {@link #findSucceeded}</b>，虽然两者 SQL 只差一个条件：
     * 那个方法的语义是"同一批数据有没有算过"，必须<b>跨策略</b>（delta 的结论也是结论）；
     * 这个方法要的是"增量该基于哪一份完整结果"，必须<b>只要 full</b>。
     * 混成一个方法就会出现 delta 基于 delta 的链式更新 —— 每一代都在上一代结论上再压缩一次，
     * 三代之后主线退化成正确的废话，且上一代编错的引用会被继承下来、再也纠不回来。
     *
     * <p>带上 {@code window_key}：跨天的 full 不能当 base（旧结论整份作废）；
     * 带上 {@code params_json}：换了关键词就是另一套检索，拿别人的 base 会让增量对不上。
     */
    public Optional<RunRow> findBaseFull(String templateId, String windowKey, String paramsJson) {
        List<RunRow> rows = jdbc.query("""
                SELECT %s FROM agent_run r
                 WHERE r.template_id = ? AND r.window_key = ? AND r.params_json = ?
                   AND r.strategy = 'full' AND r.status = 'succeeded'
                   AND r.kind = 'template'
                 ORDER BY r.created_at DESC LIMIT 1
                """.formatted(COLUMNS), ROW, templateId, windowKey, paramsJson);
        return rows.stream().findFirst();
    }

    /**
     * 今天这个模板（同参数）已经成功跑过几次 delta。
     *
     * <p>只数 {@code succeeded}：failed 与 rejected 没花成钱（或花了但结果是废的），
     * 计入次数会让"今天点得多了"这个信号提前触发，把用户推回更贵的全量。
     * 这点和配额口径一致 —— 都数成功的那一行。
     */
    public int countDeltaToday(String templateId, String windowKey, String paramsJson) {
        Integer v = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_run
                 WHERE template_id = ? AND window_key = ? AND params_json = ?
                   AND strategy = 'delta' AND status = 'succeeded'
                   AND kind = 'template'
                """, Integer.class, templateId, windowKey, paramsJson);
        return v == null ? 0 : v;
    }

    /**
     * 今天成功生成了几次（配额账本）。
     *
     * <p><b>为什么不建额度表</b>：文档 §10.7 的扣减规则和 run 的状态几乎一一对应 ——
     * 成功扣 1、guard 拒绝不扣、缓存命中不扣（本来就没有新行）、失败不扣。
     * 直接数 {@code status='succeeded'} 的行，规则和实现天然一致，
     * 而且每一次扣费都能对应到具体的 run 记录，审计时点得开。
     * 另建一张额度表，两边迟早会记岔，且岔了没人发现。
     *
     * @param actor null = 全站总数；非 null = 只数这个账号
     */
    public long countSucceededSince(long sinceEpoch, String actor) {
        if (actor == null) {
            Long v = jdbc.queryForObject("""
                    SELECT COUNT(*) FROM agent_run WHERE status='succeeded' AND created_at >= ?
                    """, Long.class, sinceEpoch);
            return v == null ? 0 : v;
        }
        Long v = jdbc.queryForObject("""
                SELECT COUNT(*) FROM agent_run
                 WHERE status='succeeded' AND created_at >= ? AND trigger_by = ?
                """, Long.class, sinceEpoch, actor);
        return v == null ? 0 : v;
    }

    /**
     * 一段时间的运行汇总，供 {@code /api/agent/health} 用。
     *
     * <p><b>一条 SQL 出全部计数，而不是五个 count。</b>体检接口会被监控按秒打，
     * 五个独立查询在不锁库的 SQLite 上没问题，但它们之间没有事务边界 ——
     * 五次查询中间正好插进一次生成，就会看到"成功 3、失败 1、总数 3"这种自相矛盾的数字。
     * 单条语句拿到的至少是同一时刻的快照。
     *
     * <p>{@code runningSince} 用 {@code MIN(CASE ...)} 一并取出，而不是另查一次：
     * 僵尸判定的判据是"最早那条 running 挂了多久"，它和 {@code running} 计数必须是同一时刻的。
     *
     * @param since  起始时间，epoch 秒；当天 00:00 传入即"今日"
     * @param running 当前仍处 {@code running} 的行数（>0 不一定是问题，见 {@code oldestAgeSec}）
     * @param runningSince 最早那条 running 的创建时间；没有 running 时为 0
     */
    public record Stats(long succeeded, long failed, long rejected, long running, long runningSince,
                        long tokensIn, long tokensOut) {
    }

    /** 一段时间内的运行汇总。空集合下 SUM/MIN 全是 NULL，必须 COALESCE，否则拆箱 NPE。 */
    public Stats statsSince(long since) {
        return jdbc.queryForObject("""
                SELECT COALESCE(SUM(CASE WHEN status IN ('succeeded','superseded') THEN 1 ELSE 0 END), 0) AS ok,
                       COALESCE(SUM(CASE WHEN status='failed'    THEN 1 ELSE 0 END), 0) AS bad,
                       COALESCE(SUM(CASE WHEN status='rejected'  THEN 1 ELSE 0 END), 0) AS rej,
                       COALESCE(SUM(CASE WHEN status='running'   THEN 1 ELSE 0 END), 0) AS busy,
                       COALESCE(MIN(CASE WHEN status='running'   THEN created_at END), 0)  AS since,
                       COALESCE(SUM(tokens_in),  0) AS tin,
                       COALESCE(SUM(tokens_out), 0) AS tout
                  FROM agent_run
                 WHERE created_at >= ?
                """,
                (rs, i) -> new Stats(rs.getLong("ok"), rs.getLong("bad"), rs.getLong("rej"),
                        rs.getLong("busy"), rs.getLong("since"),
                        rs.getLong("tin"), rs.getLong("tout")),
                since);
    }

    /** 写一步执行记录。探测"钱花在哪"时，这是唯一能看到 prompt 长度与耗时的东西。 */
    public void insertStep(String runId, int seq, String kind, int promptChars, String rawOutput,
                           int tokensIn, int tokensOut, long latencyMs, String error) {
        jdbc.update("""
                INSERT INTO agent_step
                    (run_id, seq, kind, prompt_chars, raw_output, tokens_in, tokens_out, latency_ms, error, ts)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, runId, seq, kind, promptChars, clip(rawOutput, 4000), tokensIn, tokensOut,
                latencyMs, clip(error, 500), now());
    }

    /**
     * 把上次进程留下的 running 行标成 failed。
     *
     * <p>{@code running} 是一个进程内的临时状态，重启后不可能是真的"仍在运行"。
     * 不清理的话，这些行会永远挂着，且因为配额只数 succeeded，账上看不出来 ——
     * 表现为"界面上显示生成中，其实早就死了"。
     *
     * @return 清理的行数
     */
    public int resetStuck() {
        return jdbc.update("""
                UPDATE agent_run
                   SET status='failed', error='进程重启，状态复位', finished_at=?
                 WHERE status='running'
                """, now());
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static String clip(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * 一次 run 的结果快照。
     *
     * @param id             runId
     * @param templateId     模板
     * @param windowKey      窗口键（当天日期），跨天判定用
     * @param strategy       full / delta
     * @param baseRunId      delta 所基于的 full
     * @param watermark      生成时的 max(news.id)
     * @param status         running / succeeded / failed / rejected —— claim 要靠它区分
     *                       "别人跑完了"和"别人正在跑"
     * @param answerJson     输出契约的 JSON
     * @param citationsJson  校验通过的 id 列表
     * @param candidates     送进模型的条数
     * @param tokensIn       输入 token（如实记录，失败也有）
     * @param tokensOut      输出 token
     * @param model          模型自报名
     * @param createdAt      创建时间，epoch 秒
     */
    public record RunRow(
            String id,
            String templateId,
            String windowKey,
            String strategy,
            String baseRunId,
            long watermark,
            String status,
            String answerJson,
            String citationsJson,
            /** 过程步骤的 JSON 数组；老数据与缓存命中时为 null */
            String stepsJson,
            int candidates,
            int tokensIn,
            int tokensOut,
            String model,
            long createdAt) {
    }
}
