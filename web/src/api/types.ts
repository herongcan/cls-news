/** 后端 /api 的契约。字段与 com.herc.clsnews.model.NewsView 一一对应，改动时两边一起改。 */

export type Sentiment = 'bullish' | 'bearish' | 'neutral'

/** pending = 等你点；analyzing = 已提交给模型；failed = 上次失败可重试 */
export type AnalysisStatus = 'pending' | 'analyzing' | 'failed' | 'done' | string

/** 财联社原文自带的股票标注 */
export interface StockRef {
  name?: string
  code?: string
}

/** 模型判出来的个股，比原文多 market / impact / reason */
export interface AnalysisStock extends StockRef {
  market?: string
  impact?: Sentiment
  reason?: string
}

export interface Analysis {
  sentiment?: Sentiment
  confidence?: number
  summary?: string
  sectors?: string[]
  stocks?: AnalysisStock[]
  /** 原文没标个股、方向靠板块映射得出时才有 */
  inference?: string
}

export interface NewsItem {
  id: number
  /** epoch 秒，不是毫秒 */
  time: number
  level?: string
  title?: string
  text?: string
  url?: string
  stocks?: StockRef[]
  analysis?: Analysis | null
  analysisStatus?: AnalysisStatus
}

export interface Stats {
  total: number
  pending: number
  /** guest 角色下这两个字段不下发 */
  visitToday?: number
  visitTotal?: number
  /** 今天已用掉的分析次数 / 配额上限；配额按角色算，游客是 10，可写账号是 300 */
  analyzedToday?: number
  analysisCap?: number
  /** 已达上限：数字要变色，并把 title 换成「明天再试」，否则点分析只会收到 429 */
  analysisCapped?: boolean
  source?: string
  intervalMs?: number
  /** 最近一轮抓取开始的时间（epoch 秒）。lastFetched 是这一轮取回的条数，不是时间 */
  lastRunAt?: number
  lastFetched?: number
  lastInserted?: number
  lastError?: string
  runs?: number
  fulltext?: boolean
  analysisEnabled?: boolean
  /** true = 抓进来就自动分析；false（当前默认）= 只有用户点「分析」才调模型 */
  analysisAuto?: boolean
  analysisProvider?: string
  analysisModel?: string
}

export interface NewsPage {
  items: NewsItem[]
  /**
   * 增量游标：这次真的交付出去的最后一条 id。
   * 前端只能拿它推进 since —— 用 maxId 会在积压超过一页时把没交付的条目跳过。
   */
  nextSince?: number
  /** 游标之后还有积压，需要继续追。首屏/翻历史的含义是「还能往下翻」 */
  hasMore: boolean
  /** 全局最大 id。只为兼容保留，不要拿它当增量游标 */
  maxId: number
  serverTime: number
  total?: number
  stats?: Stats
}

export type Role = 'rw' | 'ro' | 'guest'

export interface StatusBody extends Stats {
  role: Role
}

export interface AnalyzeResponse {
  item: NewsItem
  cached: boolean
}
