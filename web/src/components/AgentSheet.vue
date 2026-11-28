<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { agentAsk, agentPrecheck, agentRun, agentTemplates } from '@/api/agent'
import type { AgentPrecheck, AgentResult, AgentTemplate } from '@/api/agent'
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
  if (p.strategy === 'cached') return '已有结论，未消耗 token'
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
  if (p.strategy === 'cached' || p.strategy === 'rejected') return false
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
  if (p.strategy === 'cached') return '已最新'
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
  if (p.strategy === 'cached' || p.strategy === 'rejected') return ''
  const save = p.baselineTokens - p.estimateTokens
  return '本次约 ' + k(p.estimateTokens) + ' token'
    + (save > 0 ? '（全量 ' + k(p.baselineTokens) + '）' : '')
})

const emptyHint = computed(() => {
  if (busy.value === 'pre') return '正在读今日电报…'
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

async function run(): Promise<void> {
  if (!canClick.value) return
  err.value = ''
  busy.value = 'run'
  try {
    const r = await agentAsk(active.value, applied.value)
    if (r.status === 'rejected') {
      result.value = null
      rejected.value = r
    } else {
      result.value = r
      rejected.value = null
    }
    // 生成完再报一次价：这时应判 cached、按钮失去作用 —— 恰好证明"再点一次不会再花钱"。
    // 报价失败不影响已经拿到的结论：钱已经花完了，界面不该因为一次免费请求失败而变空
    try {
      pre.value = await agentPrecheck(active.value, applied.value)
    } catch {
      /* 忽略 */
    }
  } catch (e) {
    err.value = msg(e)
  } finally {
    busy.value = ''
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
    if (v) void boot()
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
