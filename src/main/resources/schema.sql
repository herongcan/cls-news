-- 幂等建表：每次启动都会执行，已存在则跳过
-- 注意 id 故意不用 AUTOINCREMENT：本表靠 item_id 唯一约束去重，
-- 每次抓取 50 条里绝大多数是重复的，AUTOINCREMENT 会在冲突时也消耗序号，
-- 导致 id 每小时虚增两千。普通 INTEGER PRIMARY KEY 会复用「未落库的序号」，
-- id 就能和发布时间保持紧凑的单调关系。
CREATE TABLE IF NOT EXISTS news (
    id              INTEGER PRIMARY KEY,
    item_id         TEXT    NOT NULL UNIQUE,          -- 来源侧唯一 id，去重靠它
    title           TEXT    NOT NULL DEFAULT '',
    content         TEXT    NOT NULL DEFAULT '',
    url             TEXT,
    publish_time    INTEGER NOT NULL,                 -- epoch 秒
    level           TEXT,                             -- A / B / C，A 最重要
    stocks_json     TEXT,                             -- 来源自带的关联股票
    created_at      INTEGER NOT NULL,
    analysis_status TEXT    NOT NULL DEFAULT 'pending',
                                                      -- pending | done | failed | skipped
    analysis_json   TEXT,                             -- 大模型输出，见 prompts/analyze-news.md
    analyzed_at     INTEGER,
    model           TEXT
);

CREATE INDEX IF NOT EXISTS idx_news_publish ON news (publish_time DESC, id DESC);
CREATE INDEX IF NOT EXISTS idx_news_status  ON news (analysis_status);
