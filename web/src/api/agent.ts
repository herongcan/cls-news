import { request } from './http'

/**
 * 主线（模板分析）的接口契约，见 docs/agent-plan.md §9。
 *
 * <p>类型不放 {@code api/types.ts}：那个文件的抬头就写明"字段与 NewsView 一一对应"，
 * 而主线是另一套契约（结论 / 引用 / 判定）。混在一起之后，改动时很难判断
 * "这个字段到底哪边在用"。
 */

/** 一条主线。refs 是<b>已校验过</b>的新闻 id（后端会把越界的剥掉），不会出现编造的号。 */
export interface AgentBullet {
  text: string
  refs: number[]
}

export interface AgentMeta {
  /** full / delta，界面上只在排查时看 */
  strategy?: string
  candidates?: number
  tokensIn?: number
  tokensOut?: number
  watermark?: number
  /** true = 这次没有调模型，返回的是已有结果 */
  cached?: boolean
  /** epoch 秒 */
  generatedAt?: number
  model?: string
}

export interface AgentResult {
  runId: string
  templateId: string
  /** succeeded / rejected / running / failed */
  status: string
  title?: string
  bullets: AgentBullet[]
  /** 拒答时的机器码：no-data / min-hits / off-topic / empty-answer */
  reason?: string | null
  /** 拒答或失败时给用户看的一句话 */
  detail?: string | null
  /** 非错误路径的提示，例如"数据没有变化，直接返回上一版" */
  note?: string | null
  /** 语气：stale 表示眼前是旧结论（回退），要转琥珀并挂「旧版」；info 用最弱的灰 */
  noteKind?: 'info' | 'stale' | null
  meta: AgentMeta
}

export interface AgentTemplate {
  id: string
  title: string
  /** false = 每次全量，不参与缓存与增量 */
  cacheable: boolean
  window: string
  minHits: number
  costHint?: string
  keywords: string[]
}

export interface AgentPrecheck {
  templateId: string
  title: string
  cacheable: boolean
  window: string
  /** full / delta / cached / rejected */
  strategy: 'full' | 'delta' | 'cached' | 'rejected' | string
  /** 判定的机器码，排查用 */
  reason?: string
  /** 模型是否就绪。false 时还能看已有结论，但点下去必然失败 */
  ready: boolean
  /** 已有 full 之后新增的候选条数 */
  newCount: number
  candidates: number
  /** 这次点下去预计消耗的输入 token；cached / rejected 为 0 */
  estimateTokens: number
  /** 同一批数据走全量的价格，用来让"省了多少"可验证 */
  baselineTokens: number
  watermark: number
  /** 已有结论的生成时间，没有则 0 */
  lastRunAt: number
  /** 缓存命中时可直接读的 runId */
  runId?: string | null
  /** 当日已有 full 的 runId：先当"当前版本"显示，按钮才是「更新」 */
  baseRunId?: string | null
  /** 今天还剩几次 */
  remaining: number
  note?: string | null
  detail?: string | null
}

export function agentTemplates(): Promise<{ items: AgentTemplate[] }> {
  return request<{ items: AgentTemplate[] }>('/api/agent/templates')
}

/**
 * 免费报价。打开浮层时调它，不产生 token。
 *
 * @param keywords 追加的检索关键词，空表示只用模板自带的
 */
export function agentPrecheck(templateId: string, keywords = ''): Promise<AgentPrecheck> {
  return request<AgentPrecheck>('/api/agent/precheck', {
    query: { templateId, keywords: keywords || undefined }
  })
}

/**
 * 真生成。超时给到 2 分钟：后端同步等模型，输入一万 token 的 prefill 本身就要几秒，
 * 默认 15 秒会在慢响应上误判失败 —— 而失败的那次钱已经花出去了。
 */
export function agentAsk(templateId: string, keywords = ''): Promise<AgentResult> {
  return request<AgentResult>('/api/agent/ask', {
    method: 'POST',
    body: { templateId, keywords: keywords || undefined },
    timeout: 120000
  })
}

/** 结果快照：缓存命中时直接按 runId 把结论读回来，不用再让用户点一次。 */
export function agentRun(runId: string): Promise<AgentResult> {
  return request<AgentResult>('/api/agent/runs/' + encodeURIComponent(runId))
}
