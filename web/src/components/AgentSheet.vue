<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { agentAskStream, agentPrecheck, agentRun, agentTemplates } from '@/api/agent'
import type { AgentPrecheck, AgentResult, AgentStep, AgentTemplate } from '@/api/agent'
import { ApiError } from '@/api/http'
import { stampShort } from '@/utils/format'

/**
 * 今日主线浮层：首屏结论 + chips + 输入框，见 docs/agent-plan.md §9 / §13 S1.4。
 *
 * <p>三条设计约束，都是原型阶段（docs/proto/note-overlay.html）量出来的：
 * <ol>
 *   <li>note 单独一行，挂在 meta 下面。塞进 meta 行时 21 字就折行，把「5 条主线」挤下去</li>
 *   <li>note 的可用宽度只有 290/264/224px（390/360/320），所以后端把它压到 ≤18 字，
 *       前端不做截断 —— 截出来的半句比折行更难看</li>
 *   <li>用户看到的是旧结论时必须显形（琥珀 + 旧版），纯灰会被当成装饰跳过</li>
 * </ol>
 *
 * <p><b>打开浮层不花钱</b>：首屏只打 precheck（免费），有现成结论就按 runId 读回来。
 * 只有点「生成/更新」才会走 ask。这是这个组件最重要的一个性质，改的时候别破坏它。
 */
const props = defineProps<{
  open: boolean
  /** 只读账号不给生成按钮：留着再让它 403，人会以为是坏了 */
  canRun: boolean
}>()

const emit = defineEmits<{
  (e: 'close'): void
  /** 点了某条引用里的新闻 id */
  (e: 'pick', id: number): void
}>()

const templates = ref<AgentTemplate[]>([])
const active = ref('')
const pre = ref<AgentPrecheck | null>(null)
const result = ref<AgentResult | null>(null)
const rejected = ref<AgentResult | null>(null)
const busy = ref<'' | 'pre' | 'run'>('')
const err = ref('')
/** 输入框里的字；applied 是真正参与这次报价/生成的那个 */
const kw = ref('')
const applied = ref('')

const activeTemplate = computed(() => templates.value.find((t) => t.id === active.value))

const bullets = computed(() => result.value?.bullets ?? [])

/**
 * 生成中，一行一行推回来的过程。
 *
 * <p>和 {@code result.steps} 是同一套数据、不同时刻：这里是在跑的，那里是跑完落库的。
 * 分开存而不是直接往 result 里塞，是因为生成中 result 还是上一版结论 ——
 * 把这一版的步骤写进上一版的结果里，界面会显示"上一版结论 + 这一版的取数条数"。
 */
const liveSteps = ref<AgentStep[]>([])
/**
 * 生成中，模型逐段推回来的思考文本。
 *
 * <p>和 {@code result.steps} 里 {@code k='reasoning'} 那行的 hint 是同一份，不同时刻：
 * 这里是在跑的增量，那里是落库后的全文。分开存，因为生成中 result 还是上一版。
 */
const liveReasoning = ref('')
/** 思考卡片展开态：生成中强制展开看它实时滚动，完成后折叠成摘要 */
const showReasoning = ref(false)
/** 卡片展开后，正文是否显示全文（否则只露前 N 字） */
const reasoningFull = ref(false)
/**
 * 这份结论是「这次刚流式生成出来的」还是「读回来的」。
 *
 * <p>只在 onDone 那一刻置 true，用来压住"第一次见过程自动展开"—— 那次展开的
 * 意义是让人知道"有过程可看"，而刚生成的这次过程已经在生成中展开过、又在结果
 * 到达时收起来了，watch 不该再把它顶开。读回旧结论（缓存命中）时才需要那一下。
 */
let justStreamed = false

/** 已经等了多少秒。用于模型那一步的心跳，前端自己数，不靠服务端推 */
const waited = ref(0)
let ticker: ReturnType<typeof setInterval> | null = null
/** 当前这条流的开关。关浮层、换模板都要拉，不然会挂到服务端超时 */
let stopStream: (() => void) | null = null
/**
 * 这次断开是我们自己拉的，不是服务端给的终态。
 *
 * <p>用来区分"生成完了要补一次报价"和"用户把浮层关了、什么都不用做"。
 * 没有它的话，每次关浮层都会多发一个 precheck —— 虽然免费，但纯属噪音。
 */
let manualStop = false

/**
 * 「过程」折叠区。
 *
 * <p>默认收起：它不是结论的一部分，展开后一次 7 行，会把 5 条主线挤到屏幕外。
 * 但它是唯一能回答「为什么这次只有 3 条」的地方，所以要有一个明显的入口。
 *
 * <p>拒答时也读：过程停在「材料校验 不足」，hint 就是拒答原因 ——
 * 那时 result 是 null，只看 result 会让最需要解释的场景恰好没有解释。
 *
 * <p>生成中优先用 liveSteps：此时 result 还是上一版，读它会把旧的过程摆在
 * 正在跑的新一次下面，数字全对不上。
 */
const steps = computed<AgentStep[]>(() => {
  const src = liveSteps.value.length ? liveSteps.value : result.value?.steps ?? rejected.value?.steps ?? []
  // reasoning 单独成「思考」卡片，不进过程列表 —— 它的 hint 是几百字的思考全文，
  // 塞进 7 行过程里会把整块撑爆
  return src.filter((s) => s.k !== 'reasoning')
})
const showSteps = ref(false)

/**
 * 模型的思考全文。
 *
 * <p>生成中读实时增量（{@code liveReasoning}），完成后从落库的 steps 里找
 * {@code k='reasoning'} 那行的 hint。两处必居其一：生成中 liveReasoning 有值，
 * 落库后 reasoning 那行带着全文。都空 = 这次没要到思考（或老数据）。
 */
const reasoningText = computed<string>(() => {
  if (liveReasoning.value) return liveReasoning.value
  const src = result.value?.steps ?? rejected.value?.steps ?? []
  const r = src.find((s) => s.k === 'reasoning')
  return r?.hint || ''
})

/** 思考卡片默认只露前 N 字，点「展开」看全文。N 给 120：折叠时约两行，不挤结论 */
const REASONING_PREVIEW = 120
const reasoningPreview = computed(() => {
  const t = reasoningText.value
  if (t.length <= REASONING_PREVIEW) return t
  return t.slice(0, REASONING_PREVIEW)
})
const reasoningTruncated = computed(() => reasoningText.value.length > REASONING_PREVIEW)

/**
 * 往 liveSteps 里放一行：同 k 覆盖，否则追加。
 *
 * <p>覆盖而不是追加，是因为模型那步会推两次（「等待中」→「9.9 秒」）。
 * 追加的话界面上会出现两行"调用模型"，用户不知道该信哪个。
 */
function pushStep(s: AgentStep): void {
  const at = liveSteps.value.findIndex((x) => x.k === s.k)
  if (at >= 0) liveSteps.value.splice(at, 1, s)
  else liveSteps.value.push(s)
  // 生成中必须展开：这一步的全部意义就是让人看到它在动，
  // 收起来等于什么都没做 —— 而"有过程但没展开"和"根本没过程"在屏幕上长得一样
  showSteps.value = true
}

/**
 * 模型那一步的 hint 换成实时秒数。
 *
 * <p>服务端在这一步只会推一行「等待中」，之后十几秒一个字都没有 —— 那是模型在算，
 * 它确实没有可报的数字。但"什么都没变"和"卡死了"在屏幕上长得一样，
 * 所以这一行自己走秒表。走的是本地时钟，比让服务端每秒推一次更准也更省。
 */
function stepHint(s: AgentStep): string | undefined {
  if (s.k === 'llm' && s.value === '等待中') {
    return waited.value > 0 ? '已等 ' + waited.value + ' 秒' : '已发出请求'
  }
  return s.hint || undefined
}

/**
 * 这一步是不是「正在跑」。
 *
 * <p>只有模型步有真正的 running 态：本地取数那几步是毫秒级，推回来时就已经完成，
 * 不存在「进行中」阶段。模型步先推「等待中」占位、回来后 replace 成「9.9 秒」，
 * 所以「值还是等待中」就等价于「这一步还在跑」。
 */
function isRunning(s: AgentStep): boolean {
  return busy.value === 'run' && s.k === 'llm' && s.value === '等待中'
}

function clearTicker(): void {
  if (ticker) {
    clearInterval(ticker)
    ticker = null
  }
}

/** 断流。三处要调：关浮层、换模板、组件卸载 */
function stop(): void {
  manualStop = true
  clearTicker()
  if (stopStream) {
    stopStream()
    stopStream = null
  }
}

/* 组件被销毁时也要断：否则这条连接会挂到服务端超时（那是两分钟） */
onUnmounted(stop)

/**
 * 第一次拿到<b>落库后的</b>过程时自动展开一次。
 *
 * <p>折叠区默认收起是对的（一块 7 行会挤掉结论），但"默认收起"和"根本不存在"
 * 在屏幕上长得一样 —— 反馈里两次都是"没看到过程"。所以先展开一次让人知道它在这儿，
 * 之后完全交给用户：看过就不再自动展开。
 *
 * <p>这里只盯着 {@code result.steps}（落库那份），不看 liveSteps：生成中的展开由
 * {@code onStep} 那一路显式做，两条路径不能互相打架 —— 否则「结果到了该折叠」会被
 * 「第一次见过程该展开」重新顶开，界面就停在"结论在屏上、过程也摊开"的僵持态。
 */
const STEPS_SEEN = 'agent-steps-seen'
watch(
  () => result.value?.steps?.length ?? rejected.value?.steps?.length ?? 0,
  (n) => {
    if (!n) return
    // 刚流式生成完的那次不自动展开：它已经在生成中展开过、又在 done 时收起来了，
    // 这里再顶开就等于"结果到了又把过程摊出来"，和"输出后折叠"的诉求正好相反
    if (justStreamed) {
      justStreamed = false
      return
    }
    try {
      if (localStorage.getItem(STEPS_SEEN)) return
      localStorage.setItem(STEPS_SEEN, '1')
    } catch {
      /* 隐私模式/小程序没有 localStorage：那就每次展开，不影响功能 */
    }
    showSteps.value = true
  }
)

/**
 * 有结论、但这一版没留下过程。
 *
 * <p>过程是后加的字段，升级前生成的 run 全是空的。空着不解释，用户只会得出
 * 「这个功能没做」—— 而真相是「你看到的那一版太旧」。所以宁可多一行灰字。
 */
const noSteps = computed(() => bullets.value.length > 0 && steps.value.length === 0)

const heading = computed(
  () => result.value?.title || activeTemplate.value?.title || '今日主线'
)

/** meta 行：有结论时说结论，没有结论时说价格。两种时候都只占一行。 */
const metaLine = computed(() => {
  const r = result.value
  if (r?.meta?.generatedAt) {
    const parts = [stampShort(r.meta.generatedAt) + ' 生成']
    if (r.meta.candidates) parts.push(r.meta.candidates + ' 条')
    if (bullets.value.length) parts.push(bullets.value.length + ' 条主线')
    return parts.join(' · ')
  }
  const p = pre.value
  if (!p) return ''
  if (p.strategy === 'rejected') return ''
  if (p.strategy === 'cached') return noSteps.value ? '已有结论 · 这一版没有过程数据' : '已有结论，未消耗 token'
  const cost = k(p.estimateTokens) + ' token'
  if (p.strategy === 'delta' && p.newCount > 0) return '新增 ' + p.newCount + ' 条 · 约 ' + cost
  return '预计 ' + cost
})

/**
 * note 的来源有三个，按"谁更权威"排：
 *   1. 刚生成/刚命中缓存时，后端返回的那句（它是唯一知道判定理由的人）
 *   2. 显示的是当日已有一版、但数据已经变了：这时冒号后的话由前端给
 *      （后端那句说的是"这次怎么花"，不是"你看到的不是最新"）
 *   3. 什么都没有
 */
const note = computed(() => {
  if (result.value?.note) return result.value.note
  if (busy.value === 'run') return ''
  const p = pre.value
  if (!p || !result.value) return ''
  if (p.strategy === 'cached') return p.note || ''
  if (p.newCount > 0) return '新增 ' + p.newCount + ' 条，可更新'
  return ''
})

/** stale = 眼前这份是旧结论（回退过），必须显形 */
const stale = computed(() => result.value?.noteKind === 'stale')

const rejectDetail = computed(() => {
  if (result.value) return ''
  if (rejected.value?.detail) return rejected.value.detail
  return pre.value?.strategy === 'rejected' ? pre.value.detail || '' : ''
})

const canClick = computed(() => {
  if (busy.value || !props.canRun) return false
  const p = pre.value
  if (!p) return false
  if (!p.ready) return false
  // cached 正常不给点（再点也不会更便宜）。但「结论有、过程没留下」是唯一例外：
  // 不给入口的话，用户会一直卡在一版没有过程的旧结论上，只能靠换关键词绕过去。
  if (p.strategy === 'cached') return noSteps.value
  if (p.strategy === 'rejected') return false
  return p.remaining > 0
})

const actionLabel = computed(() => {
  if (busy.value === 'run') return '生成中'
  if (busy.value === 'pre') return '…'
  if (!props.canRun) return '只读'
  const p = pre.value
  if (!p) return '生成'
  // key 没配/过期时按钮置灰：让人点一下再收到 503，等于骗一次点击
  if (!p.ready) return '模型未就绪'
  if (p.remaining <= 0) return '额度用尽'
  if (p.strategy === 'rejected') return '材料不足'
  if (p.strategy === 'cached') return noSteps.value ? '重新生成' : '已最新'
  return result.value ? '更新' : '生成'
})

/** 今天快用完了才提醒；还有几十次时挂着只会让人觉得这工具随时会停 */
const quotaHint = computed(() => {
  const n = pre.value?.remaining
  return typeof n === 'number' && n > 0 && n <= 3 ? '今日还剩 ' + n + ' 次' : ''
})

/**
 * 价格放在底部而不是按钮上：
 * 有结论在屏上时，meta 行已经被"上一版什么时候生成的"占满了；而"这次要花多少"
 * 必须和"点下去"这个动作挨着，否则一个花 1 万 token 的按钮看起来像是免费的。
 * 同时给出全量价 —— 让"省了多少"可验证，而不是自说自话。
 *
 * 没有结论时 meta 行已经在报价格了，这里不再重复。
 */
const priceHint = computed(() => {
  const p = pre.value
  if (!p || !p.ready || !result.value) return ''
  if (p.strategy === 'rejected') return ''
  // 缺过程时按钮会变成「重新生成」，这一下是真花钱，价格必须挨着按钮说
  if (p.strategy === 'cached') {
    return noSteps.value ? '重新生成约 ' + k(p.baselineTokens) + ' token' : ''
  }
  const save = p.baselineTokens - p.estimateTokens
  return '本次约 ' + k(p.estimateTokens) + ' token'
    + (save > 0 ? '（全量 ' + k(p.baselineTokens) + '）' : '')
})

const emptyHint = computed(() => {
  if (busy.value === 'pre') return '正在读今日电报…'
  // 生成中不能还说「今天还没有生成过」：那句话说的是「点一下」，而此刻已经在点了。
  // 有过程行时这里留空 —— 过程区正在逐行报进度，再说一遍只是两处文字抢注意力
  if (busy.value === 'run') {
    if (liveSteps.value.length) return ''
    return waited.value > 0 ? '正在等模型返回…' : '正在取数、打包…'
  }
  if (err.value) return ''
  const p = pre.value
  if (!p) return ''
  if (p.strategy === 'rejected') return ''
  if (!p.ready) return '模型未就绪（检查 API key）。今天还没生成过，暂时不能生成。'
  return '今天还没有生成过主线。点「生成」按今日电报提炼 3–5 条，每条都带原文引用。'
})

function k(n?: number): string {
  const v = n || 0
  return v >= 1000 ? (v / 1000).toFixed(1) + 'k' : String(v)
}

function msg(e: unknown): string {
  return e instanceof ApiError ? e.message : '请求失败'
}

/**
 * 打开时走一遍：模板 → 报价 → 有现成结论就顺带读回来。
 *
 * <p>不做"只加载一次"的缓存：precheck 免费，而数据随时在变。用户关掉再打开时，
 * 水位线可能已经变了，给他看一份两小时前的判定反而是错的。
 */
async function boot(): Promise<void> {
  err.value = ''
  rejected.value = null
  if (!templates.value.length) {
    try {
      templates.value = (await agentTemplates()).items
      if (!active.value) active.value = templates.value[0]?.id || ''
    } catch (e) {
      err.value = msg(e)
      return
    }
  }
  await refresh()
}

async function refresh(): Promise<void> {
  if (!active.value) return
  busy.value = 'pre'
  // 换模板 / 换关键词 / 重新打开都会走到这里：过程区必须收起，
  // 否则上一份结论展开着、新结论还没到，会看到一段对不上的数字。
  // liveSteps 也要清：那是上一次生成推过来的，留着会挂在新结论下面
  showSteps.value = false
  showReasoning.value = false
  reasoningFull.value = false
  liveSteps.value = []
  liveReasoning.value = ''
  clearTicker()
  waited.value = 0
  try {
    const p = await agentPrecheck(active.value, applied.value)
    pre.value = p
    if (p.strategy === 'cached' && p.runId) {
      result.value = await agentRun(p.runId)
    } else if (p.baseRunId) {
      // 有当日 base 就先把它当"当前版本"显示：一片空白加一个按钮，用户不知道会得到什么
      result.value = await agentRun(p.baseRunId)
    } else {
      result.value = null
    }
  } catch (e) {
    pre.value = null
    result.value = null
    err.value = msg(e)
  } finally {
    busy.value = ''
  }
}

/**
 * 点「生成」：开一条流，过程一行一行进来。
 *
 * <p><b>为什么不是 async/await</b>：结果不是一个 Promise，而是一串事件。
 * 等它 resolve 就等于回到了同步请求 —— 那正是这次要改掉的东西。
 * 收尾统一放在 onClose：正常、失败、中途断线都会走到那一个出口，
 * 计时器和 stopStream 只需要在一个地方清。
 */
function run(): void {
  if (!canClick.value) return
  err.value = ''

  // 点下去就切"生成中"：从点击到第一个事件之间还有建连和取数，
  // 那一小段界面必须已经变了，否则点完像是没反应
  busy.value = 'run'
  liveSteps.value = []
  liveReasoning.value = ''
  waited.value = 0
  manualStop = false
  clearTicker()
  // 点「更新」的那一刻就展开过程区：哪怕第一个 step 还没推回来，
  // 也要让人看到「过程从 0 开始重跑」—— 否则上一版折叠着、新过程还没到，
  // 界面上是「结论变了但过程没动静」，会被读成"更新没重新生成过程"
  showSteps.value = true
  ticker = setInterval(() => {
    waited.value += 1
  }, 1000)

  // 只有"屏上这版没有过程、而缓存判定不许再点"时才强制重算；其余路径照旧走缓存
  const force = pre.value?.strategy === 'cached' && noSteps.value

  stopStream = agentAskStream(active.value, applied.value, force, {
    onStep: pushStep,
    onReasoning: (chunk) => {
      // 思考逐段进来：追加到卡片里，并确保它展开着（用户在等的就是这段在动）
      liveReasoning.value += chunk
      showReasoning.value = true
    },
    onDone: (r) => {
      if (r.status === 'rejected') {
        result.value = null
        rejected.value = r
      } else {
        result.value = r
        rejected.value = null
      }
      // 结论到了就交还给库里那份：内容与 liveSteps 相同（同一次生成），
      // 但以 done 为准，才能保证"界面显示的"和"按 runId 查到的"永远一致
      liveSteps.value = []
      // 思考同理：实时增量清空，落库后的全文从 result.steps 里读（reasoningText 会切过去）
      liveReasoning.value = ''
      // 结果出来了，过程收起来：过程的价值在"生成中看着它动"，结论到了就该把屏幕还给主线。
      // 想看过程的人自己点开 —— 这里不再替他展开（展开的默认值交给"第一次看过"那次）
      showSteps.value = false
      showReasoning.value = false
      reasoningFull.value = false
      // 标记"这次是刚生成的"，压住 watch 的"第一次见过程自动展开"，避免结果又被顶开
      justStreamed = true
    },
    onError: (e) => {
      err.value = msg(e)
      // 失败后过程区留着已经走过的那几行 —— 「卡在调用模型」和「卡在解析」
      // 都只是一句报错，正是这几行把它们分开
    },
    onClose: () => {
      busy.value = ''
      clearTicker()
      stopStream = null
      // 主动断的（关浮层、换模板）不该再发请求：那个免费的报价是给"生成完"用的
      if (!manualStop) void refreshQuietly()
    }
  })
}

/**
 * 生成完再报一次价：这时应判 cached、按钮失去作用 —— 恰好证明"再点一次不会再花钱"。
 * 失败不影响已经拿到的结论：钱已经花完了，界面不该因为一次免费请求失败而变空。
 */
async function refreshQuietly(): Promise<void> {
  try {
    pre.value = await agentPrecheck(active.value, applied.value)
  } catch {
    /* 忽略 */
  }
}

/** 换 chip：先清空旧结论，避免"点了 A 还看到 B 的内容" */
async function choose(id: string): Promise<void> {
  if (id === active.value || busy.value) return
  active.value = id
  result.value = null
  rejected.value = null
  pre.value = null
  await refresh()
}

/**
 * 输入框回车 = 用这个关键词重新报价，不是直接生成。
 *
 * <p>一次生成约 1 万 token。把"输入并按回车"直接接到花钱的动作上，
 * 打错一个字就是一次完整计费；中间隔一次免费报价（报价里带着新增条数与价格），
 * 用户看到的才是"我要为这 12 条花 6k token"，而不是"我按了回车"。
 *
 * <p>再按一次同样的词 = 取消这个关键词（输入框里那个字不再参与检索）。
 */
async function applyKeyword(): Promise<void> {
  const next = kw.value.trim()
  if (next === applied.value) {
    if (!next) return
    applied.value = ''
    kw.value = ''
  } else {
    applied.value = next
  }
  result.value = null
  rejected.value = null
  await refresh()
}

/*
 * immediate 不能省：从 /#brief 直达时，组件挂载那一刻 open 就已经是 true，
 * watch 没有"变化"可等 —— 少了它浮层会是一个标题加一片空白（真踩过）。
 */
watch(
  () => props.open,
  (v) => {
    if (v) {
      void boot()
    } else {
      // 关掉浮层就把流断掉。注意服务端不会因此停下 —— 模型已经发出去了，钱照花、
      // 结论照落库，下次打开按 runId 还能读回来。这里只是不再听
      stop()
    }
    // 桌面端：Esc 关闭。手机上用的是点遮罩
    // #ifdef H5
    if (v) window.addEventListener('keydown', onKey)
    else window.removeEventListener('keydown', onKey)
    // #endif
  },
  { immediate: true }
)

function onKey(e: KeyboardEvent): void {
  if (e.key === 'Escape') emit('close')
}
</script>

<template>
  <view v-if="open" class="mask" @tap="emit('close')">
    <view class="sheet" @tap.stop>
      <view class="grab" />

      <view class="head">
        <view class="hl">
          <text class="st">{{ heading }}</text>
          <text v-if="metaLine" class="smeta">{{ metaLine }}</text>
          <view v-if="note" class="snote" :class="{ stale }">
            <text v-if="stale" class="oldtag">旧版</text>
            <text class="ntext">{{ note }}</text>
          </view>
        </view>
        <view class="act" :class="{ busy: busy === 'run', off: !canClick }" @tap="run">
          <text v-if="busy === 'run'" class="spin" />
          <text>{{ actionLabel }}</text>
        </view>
      </view>

      <view class="body">
        <view v-if="rejectDetail" class="reject">
          <text class="rt">这次没有生成</text>
          <text class="rd">{{ rejectDetail }}</text>
        </view>
        <view v-else-if="err" class="reject">
          <text class="rt">出错了</text>
          <text class="rd">{{ err }}</text>
        </view>
        <text v-else-if="!bullets.length" class="empty">{{ emptyHint }}</text>

        <view v-for="(b, i) in bullets" :key="i" class="bl">
          <text class="idx">{{ i + 1 }}</text>
          <view class="bt">
            <text class="tx">{{ b.text }}</text>
            <view class="refs">
              <text
                v-for="r in b.refs"
                :key="r"
                class="ref"
                @tap="emit('pick', r)"
              >[[{{ r }}]]</text>
            </view>
          </view>
        </view>

        <!-- 思考：模型逐段吐的过程，独立成卡片。生成中实时滚动，完成后折叠成摘要 -->
        <view v-if="reasoningText" class="think">
          <view class="think-hd" @tap="showReasoning = !showReasoning">
            <text class="proc-ar" :class="{ open: showReasoning }">›</text>
            <text class="think-t">思考</text>
            <text class="think-n">
              {{ busy === 'run' ? '正在思考…' : reasoningText.length + ' 字' }}
            </text>
          </view>
          <view v-if="showReasoning" class="think-body">
            <!-- 只露前 N 字；全文要再点一次「展开全文」。折叠成摘要时不显示正文 -->
            <text class="think-txt">{{ reasoningFull ? reasoningText : reasoningPreview }}</text>
            <text
              v-if="reasoningTruncated && !reasoningFull"
              class="think-more"
              @tap="reasoningFull = true"
            >展开全文</text>
            <text
              v-else-if="reasoningTruncated && reasoningFull"
              class="think-more"
              @tap="reasoningFull = false"
            >收起</text>
          </view>
        </view>

        <!-- 过程：生成中逐步骤展开，结果出来后收成「已完成」摘要条 -->
        <view v-if="steps.length" class="proc">
          <view class="proc-hd" @tap="showSteps = !showSteps">
            <text class="proc-ar" :class="{ open: showSteps }">›</text>
            <text class="proc-t">{{ busy === 'run' ? '思考过程' : '生成过程' }}</text>
            <!-- 生成中不说"5 步"：那个数字还在涨，报一个中途的数只会让人以为就这么多 -->
            <text class="proc-n">{{ busy === 'run' ? '进行中' : '已完成 · ' + steps.length + ' 步' }}</text>
          </view>
          <view v-if="showSteps" class="proc-list">
            <!-- key 用 k 不用下标：覆盖同一步（等待中 → 9.9 秒）时复用的是同一个节点，
                 用下标会让下一行错位重渲染，看起来像整块在闪 -->
            <view
              v-for="s in steps"
              :key="s.k"
              class="proc-row"
              :class="{ running: isRunning(s), done: !isRunning(s) }"
            >
              <!-- 状态图标：跑着是转圈，完成是对勾 -->
              <view class="proc-ic" :class="{ run: isRunning(s) }">
                <text v-if="isRunning(s)" class="proc-spin" />
                <text v-else class="proc-tick">✓</text>
              </view>
              <text class="proc-l">{{ isRunning(s) ? '正在思考' : s.label }}</text>
              <text class="proc-v">
                <template v-if="isRunning(s)">
                  <text class="proc-ellipsis"><text>·</text><text>·</text><text>·</text></text>
                </template>
                <template v-else>{{ s.value }}</template>
              </text>
              <text v-if="stepHint(s)" class="proc-h">{{ stepHint(s) }}</text>
            </view>
          </view>
        </view>
        <!-- 没过程要说明：静默不显示会被读成「这个功能没做」 -->
        <text v-else-if="noSteps" class="proc-gap">
          这一版生成于过程记录上线前，没有过程数据。重新生成一次即可看到。
        </text>
      </view>

      <view class="foot">
        <view v-if="templates.length > 1" class="chips">
          <text
            v-for="t in templates"
            :key="t.id"
            class="chip"
            :class="{ on: t.id === active }"
            @tap="choose(t.id)"
          >{{ t.title }}</text>
        </view>

        <view class="askrow">
          <input
            v-model="kw"
            class="qin"
            placeholder="加个关键词，例如「降准」"
            confirm-type="search"
            @confirm="applyKeyword"
          />
          <text class="send" :class="{ off: busy !== '' }" @tap="applyKeyword">↑</text>
        </view>

        <view class="fineprint">
          <text>{{ priceHint || (applied ? '只看「' + applied + '」· 再点 ↑ 取消' : '引用可点，跳回原文') }}</text>
          <text v-if="quotaHint" class="quota">{{ quotaHint }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
/* 遮罩压暗而不是全遮：让人看得出来东西还在下面（和重登浮层同一套语义） */
.mask {
  position: fixed;
  inset: 0;
  z-index: 80;
  display: flex;
  align-items: flex-end;
  justify-content: center;
  background: rgba(10, 11, 13, 0.62);
  -webkit-backdrop-filter: blur(6px);
  backdrop-filter: blur(6px);
}
.theme-light .mask {
  background: rgba(250, 249, 246, 0.68);
}

.sheet {
  width: 100%;
  max-width: 560px;
  max-height: 86vh;
  display: flex;
  flex-direction: column;
  background: var(--panel);
  border-top: 0.5px solid var(--line-2);
  border-radius: 16px 16px 0 0;
  padding: 6px 20px calc(14px + env(safe-area-inset-bottom));
  box-shadow: 0 -18px 48px rgba(0, 0, 0, 0.32);
  animation: rise 0.22s ease-out;
}
@keyframes rise {
  from {
    transform: translateY(14px);
    opacity: 0.6;
  }
  to {
    transform: translateY(0);
    opacity: 1;
  }
}
@media (prefers-reduced-motion: reduce) {
  .sheet {
    animation: none;
  }
}

.grab {
  width: 34px;
  height: 3px;
  border-radius: 2px;
  background: var(--line-2);
  margin: 4px auto 10px;
  flex: none;
}

/* ---------- 头部 ---------- */
.head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex: none;
}
.hl {
  flex: 1;
  min-width: 0;
}
.st {
  display: block;
  font-size: 15px;
  font-weight: 500;
  letter-spacing: 0.2px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}
.smeta {
  display: block;
  margin-top: 3px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--dim);
  font-variant-numeric: tabular-nums;
}
/*
 * note 单独一行。行内流而不是 flex+::before 分隔点：后者换行时会把「·」
 * 甩到下一行行首，看着像列表符号（原型里踩过）。
 */
.snote {
  display: block;
  margin-top: 2px;
  font-size: 11.5px;
  line-height: 1.5;
  color: var(--dim);
  overflow-wrap: anywhere;
}
.snote.stale {
  color: var(--amber);
}
.oldtag {
  display: inline-block;
  font-size: 10px;
  line-height: 1.4;
  padding: 0 4px;
  margin-right: 5px;
  border-radius: 3px;
  border: 0.5px solid var(--amber-line);
  color: var(--amber);
  vertical-align: 1px;
}

.act {
  flex: none;
  font-size: 12px;
  color: var(--text);
  border: 0.5px solid var(--line-2);
  border-radius: 7px;
  padding: 5px 11px;
  cursor: pointer;
  display: flex;
  align-items: center;
  gap: 5px;
}
.act.busy {
  opacity: 0.6;
  cursor: default;
}
.act.off {
  color: var(--dim);
  border-color: var(--line);
  cursor: default;
}
.spin {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  border: 1.5px solid var(--dim);
  border-top-color: transparent;
  animation: turn 0.7s linear infinite;
}
@keyframes turn {
  to {
    transform: rotate(360deg);
  }
}
@media (prefers-reduced-motion: reduce) {
  .spin {
    animation: none;
  }
}

/* ---------- 结论 ---------- */
.body {
  min-height: 0;
  overflow-y: auto;
  -webkit-overflow-scrolling: touch;
  margin: 12px 0 4px;
  padding-bottom: 2px;
  border-top: 0.5px solid var(--line);
  padding-top: 12px;
}

.bl {
  display: flex;
  gap: 9px;
  padding: 7px 0;
}
.idx {
  width: 15px;
  flex: none;
  font-size: 12px;
  line-height: 1.6;
  color: var(--dim);
  font-variant-numeric: tabular-nums;
  padding-top: 1px;
}
.bt {
  flex: 1;
  min-width: 0;
}
.tx {
  font-size: 14px;
  line-height: 1.6;
  color: var(--text);
}
.refs {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 4px;
}
.ref {
  font-size: 11px;
  line-height: 1.5;
  color: var(--muted);
  border: 0.5px solid var(--line-2);
  border-radius: 4px;
  padding: 0 5px;
  font-variant-numeric: tabular-nums;
  cursor: pointer;
}

.empty,
.reject {
  display: block;
  padding: 6px 0 10px;
}
.empty {
  font-size: 12.5px;
  line-height: 1.7;
  color: var(--dim);
}
.rt {
  display: block;
  font-size: 12.5px;
  color: var(--amber);
}
.rd {
  display: block;
  margin-top: 3px;
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
}

/* ---------- 思考 ---------- */
/*
 * 思考卡片和「过程」是两回事：过程是代码算出来的确定性数字，思考是模型自述的推理。
 * 后者的可信度低一档（不可验证），所以视觉上更轻、和结论之间不加那根「可信度分界」线，
 * 只是并列在过程上面。标题行复用 .proc-hd / .proc-ar 的折叠交互，不另造一套箭头。
 */
.think {
  margin-top: 12px;
  padding-top: 6px;
  border-top: 0.5px solid var(--line);
}
.think-hd {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 0;
  cursor: pointer;
}
.think-t {
  font-size: 11.5px;
  color: var(--dim);
}
.think-n {
  margin-left: auto;
  font-size: 11px;
  color: var(--amber);
  opacity: 0.85;
  font-variant-numeric: tabular-nums;
}
.think-body {
  padding: 2px 0 6px 14px;
}
.think-txt {
  font-size: 12px;
  line-height: 1.7;
  color: var(--muted);
  /* 思考是多段连续文本，pre-wrap 保留模型吐出来的换行，别挤成一大坨 */
  white-space: pre-wrap;
  word-break: break-word;
}
.think-more {
  display: block;
  margin-top: 4px;
  font-size: 11.5px;
  color: var(--amber);
  opacity: 0.9;
}

/* ---------- 过程 ---------- */
/*
 * 一条细线隔开「结论」和「过程」：这两块的可信度不是一回事 ——
 * 上面每条都有可回查的引用，下面是流水线的计数器。混在一起会互相借光。
 */
.proc {
  margin-top: 12px;
  padding-top: 6px;
  border-top: 0.5px solid var(--line);
}
/* 与「过程」同一位置的一行说明：没有过程时，那根线照样在，用户才不会以为这里是空的 */
.proc-gap {
  display: block;
  margin-top: 12px;
  padding-top: 8px;
  border-top: 0.5px solid var(--line);
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--dim);
}
.proc-hd {
  display: flex;
  align-items: center;
  gap: 4px;
  padding: 5px 0;
  cursor: pointer;
}
.proc-ar {
  display: inline-block;
  width: 10px;
  font-size: 11px;
  color: var(--dim);
  transition: transform 0.16s ease;
}
.proc-ar.open {
  transform: rotate(90deg);
}
.proc-t {
  font-size: 11.5px;
  color: var(--dim);
}
.proc-n {
  margin-left: auto;
  font-size: 11px;
  color: var(--dim);
  opacity: 0.7;
  font-variant-numeric: tabular-nums;
}
.proc-list {
  padding: 2px 0 6px;
}
.proc-row {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px;
  padding: 3px 0;
  /* 新到的一行淡入。覆盖同一步时 key 不变、节点被复用，所以不会重播 ——
     这一点很重要：模型那步从「等待中」变成「9.9 秒」时若整块闪一下，看着像出错了 */
  animation: procIn 0.18s ease-out;
}
@keyframes procIn {
  from {
    opacity: 0;
    transform: translateY(-2px);
  }
  to {
    opacity: 1;
    transform: none;
  }
}
/* 状态图标列：对勾（完成）或转圈（正在思考）。固定在行首占 14px，不参与换行 */
.proc-ic {
  width: 14px;
  flex: none;
  display: flex;
  align-items: center;
  justify-content: center;
}
.proc-tick {
  font-size: 10px;
  line-height: 1;
  color: var(--muted);
  opacity: 0.85;
}
.proc-spin {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  border: 1.5px solid var(--amber);
  border-top-color: transparent;
  animation: procTurn 0.7s linear infinite;
}
@keyframes procTurn {
  to {
    transform: rotate(360deg);
  }
}
/* 正在思考那一行的省略号：三个点依次跳动，替代静态的「已等 N 秒」，
   让"在干活"这件事看得见，而不是一个冷冰冰的秒数 */
.proc-ellipsis {
  display: inline-flex;
  gap: 2px;
  letter-spacing: 1px;
}
.proc-ellipsis text {
  animation: procDot 1.2s infinite;
}
.proc-ellipsis text:nth-child(2) {
  animation-delay: 0.2s;
}
.proc-ellipsis text:nth-child(3) {
  animation-delay: 0.4s;
}
@keyframes procDot {
  0%,
  80%,
  100% {
    opacity: 0.2;
  }
  40% {
    opacity: 1;
  }
}
/* 正在思考的行：label 转成强调色，让"还没完成"和"已经完成"一眼分开 */
.proc-row.running .proc-l {
  color: var(--amber);
}
.proc-l {
  font-size: 11.5px;
  line-height: 1.4;
  color: var(--dim);
  min-width: 54px;
}
.proc-v {
  margin-left: auto;
  font-size: 11.5px;
  line-height: 1.4;
  color: var(--text);
  font-variant-numeric: tabular-nums;
}
/* hint 单独占一行：和 label/value 挤一行时，390px 下「含正文 48 条，仅标题 21 条」会被折成两截 */
.proc-h {
  flex-basis: 100%;
  font-size: 10.5px;
  line-height: 1.45;
  color: var(--dim);
  opacity: 0.72;
}

/* ---------- 底部 ---------- */
.foot {
  flex: none;
  border-top: 0.5px solid var(--line);
  padding-top: 10px;
}
.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 9px;
}
.chip {
  font-size: 11.5px;
  line-height: 1.6;
  color: var(--muted);
  border: 0.5px solid var(--line-2);
  border-radius: 10px;
  padding: 1px 9px;
  cursor: pointer;
}
.chip.on {
  color: var(--text);
  border-color: var(--dim);
  background: var(--bg);
}

.askrow {
  display: flex;
  align-items: center;
  gap: 8px;
}
/* 16px 是硬要求：iOS Safari 遇到小于 16px 的输入框会自动放大整页且不缩回 */
.qin {
  flex: 1;
  min-width: 0;
  height: 38px;
  background: var(--bg);
  border: 0.5px solid var(--line-2);
  border-radius: 9px;
  color: var(--text);
  font-size: 16px;
  padding: 0 12px;
}
.send {
  width: 34px;
  height: 34px;
  flex: none;
  border-radius: 50%;
  background: var(--text);
  color: var(--on-accent);
  font-size: 15px;
  line-height: 34px;
  text-align: center;
  cursor: pointer;
}
.send.off {
  opacity: 0.4;
}

.fineprint {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  margin-top: 7px;
  font-size: 10.5px;
  color: var(--dim);
}
.quota {
  color: var(--amber);
  flex: none;
}
</style>
