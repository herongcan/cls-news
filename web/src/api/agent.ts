import { request, ApiError } from './http'
import { streamPost } from './sse'

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

/**
 * 「过程」里的一步：确定性数字，不是模型的思考。
 *
 * <p>{@code label} 与 {@code value} 都被后端压到很短（6 / 10 字），
 * 前端不截断 —— 截出来的半句比折行更难看。{@code hint} 另起一行小字。
 */
export interface AgentStep {
  /** scan / filter / merge / pack / guard / delta / llm / cite */
  k: string
  label: string
  value: string
  hint?: string | null
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
  /**
   * 这份结论是怎么来的。老数据为空数组（那时还没记过程），前端据此不渲染那一块。
   * 缓存命中时读的是库里那份，所以它能解释「昨天那版」而不是「这次将要做什么」。
   */
  steps?: AgentStep[]
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
  /** 拒答时也有：过程会停在「材料校验 不足」，hint 就是拒答原因 */
  steps?: AgentStep[]
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
 *
 * <p><b>新代码请用 {@link agentAskStream}</b>：这个接口要等十几秒才有一个字节回来，
 * 界面上那段时间是死的。保留它是因为它最简单 —— 脚本、curl、老版本客户端都还能用。
 */
export function agentAsk(templateId: string, keywords = '', force = false): Promise<AgentResult> {
  return request<AgentResult>('/api/agent/ask', {
    method: 'POST',
    body: { templateId, keywords: keywords || undefined, force: force || undefined },
    timeout: 120000
  })
}

export interface AgentStreamHooks {
  /** 流已建立。这一刻起才该把按钮切成"生成中" —— 之前都是"还没发出去" */
  onOpen?: () => void
  /** 走完一步。同 k 覆盖：模型那步会先来「等待中」，再来「9.9 秒」 */
  onStep?: (s: AgentStep) => void
  /** 模型思考过程的一段增量文本。逐段追加，直到模型那步被覆盖成「9.9 秒」 */
  onReasoning?: (chunk: string) => void
  /** 终态。结构与 {@link agentAsk} 的返回值完全一致，所以收尾逻辑不用写两套 */
  onDone?: (r: AgentResult) => void
  onError?: (e: ApiError) => void
  /** 流结束（正常或失败都会调），收尾用。放在这里清计时器最省心：只有这一条出口 */
  onClose?: () => void
}

/**
 * 真生成，过程一行一行推回来。
 *
 * <p><b>为什么值得多这么一层</b>：一次生成 6.9–32.9 秒（近十次实测），其中取数打包只占
 * 几十毫秒，剩下全是等模型。一次性返回的形态下，这十几秒里用户能看到的只有
 * "生成中"三个字 —— 而取数那几行其实早就有了，只是压着没给。
 *
 * @returns 中止函数。关浮层、换模板时必须调 —— 但要知道它<b>不会</b>让服务端停下：
 *          模型已经发出去了，钱照花，结论照落库，只是没人听
 */
export function agentAskStream(
  templateId: string,
  keywords: string,
  force: boolean,
  hooks: AgentStreamHooks
): () => void {
  return streamPost(
    '/api/agent/ask/stream',
    { templateId, keywords: keywords || undefined, force: force || undefined },
    {
      onOpen: hooks.onOpen,
      onStep: (d) => hooks.onStep?.(d as AgentStep),
      onReasoning: hooks.onReasoning,
      onDone: (d) => hooks.onDone?.(d as AgentResult),
      onError: hooks.onError,
      onClose: hooks.onClose
    }
  )
}

/** 结果快照：缓存命中时直接按 runId 把结论读回来，不用再让用户点一次。 */
export function agentRun(runId: string): Promise<AgentResult> {
  return request<AgentResult>('/api/agent/runs/' + encodeURIComponent(runId))
}
