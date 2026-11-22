<script setup lang="ts">
import { computed } from 'vue'
import type { AnalysisStock, NewsItem } from '@/api/types'
import { percent, quoteUrl, sentimentClass, sentimentLabel, splitByKeyword, stamp } from '@/utils/format'

const props = defineProps<{
  item: NewsItem
  keyword: string
  canAnalyze: boolean
  analyzing: boolean
}>()

const emit = defineEmits<{ (e: 'analyze'): void }>()

const t = computed(() => stamp(props.item.time))
const level = computed(() => (props.item.level || '').toUpperCase())
const segments = computed(() => splitByKeyword(props.item.text || '', props.keyword))
const stocks = computed(() => (props.item.stocks || []).filter((s) => s && (s.name || s.code)))
const analysis = computed(() => props.item.analysis || null)
const sectors = computed(() => (analysis.value?.sectors || []).filter(Boolean).slice(0, 4))

const anaStocks = computed(() =>
  (analysis.value?.stocks || [])
    .filter((s) => s && (s.name || s.code))
    .slice(0, 4)
    .map((s: AnalysisStock, i: number) => ({
      key: (s.code || s.name || '') + i,
      label: (s.market ? s.market + ' ' : '') + (s.name || s.code),
      arrow: s.impact === 'bullish' ? '↑' : s.impact === 'bearish' ? '↓' : '·',
      cls: sentimentClass(s.impact),
      tip: (s.reason || '') + (s.market ? '（' + s.market + '）' : '')
    }))
)

const actionLabel = computed(() => {
  if (props.analyzing || props.item.analysisStatus === 'analyzing') return '分析中'
  if (props.item.analysisStatus === 'failed') return '重试分析'
  return '分析'
})

/** H5 直接开行情页；小程序没有浏览器，退化成复制代码 */
function openQuote(code?: string): void {
  const url = quoteUrl(code)
  if (!url || !code) return
  // #ifdef H5
  window.open(url, '_blank', 'noopener,noreferrer')
  // #endif
  // #ifndef H5
  uni.setClipboardData({ data: code })
  // #endif
}

/**
 * 点正文打开财联社原文。
 *
 * <p>这条跳转是旧页面本来的行为（整段正文包在 {@code <a href>} 里），uni 版重写时
 * 只搬了股票 chip 的行情跳转，把正文链接整个漏了 —— 表现就是「点了没反应」。
 *
 * <p>小程序里不能直接开外链：需要 web-view 组件，而 web-view 又要求业务域名备案
 * （这个域名还没备案）。所以退化成复制链接，至少能让人自己粘到浏览器。
 */
function openSource(): void {
  const url = props.item.url
  if (!url) return
  // #ifdef H5
  window.open(url, '_blank', 'noopener,noreferrer')
  // #endif
  // #ifndef H5
  uni.setClipboardData({
    data: url,
    success: () => uni.showToast({ title: '原文链接已复制', icon: 'none' })
  })
  // #endif
}
</script>

<template>
  <view class="item">
    <view class="time">
      <text class="hm">{{ t.hm }}</text>
      <text class="dd" :class="{ today: t.today }">{{ t.day }}</text>
      <text v-if="level === 'A'" class="lv a" title="头条级消息">A</text>
      <text v-else-if="level === 'B'" class="lv b" title="宏观 / 外交 / 大盘类消息">B</text>
    </view>

    <view class="body">
      <text class="txt" :class="{ src: !!item.url }" @tap="openSource">
        <text v-for="(seg, i) in segments" :key="i" :class="{ hit: seg.hit }">{{ seg.t }}</text>
      </text>

      <view v-if="stocks.length" class="chips">
        <text
          v-for="(s, i) in stocks"
          :key="i"
          class="chip"
          @tap.stop="openQuote(s.code)"
        >{{ s.name }}<text v-if="s.code" class="code">{{ s.code }}</text></text>
      </view>

      <view class="ana">
        <template v-if="analysis">
          <text class="pill" :class="sentimentClass(analysis.sentiment)">
            {{ sentimentLabel(analysis.sentiment) }}{{ percent(analysis.confidence) }}
          </text>
          <text v-if="analysis.summary" class="why">{{ analysis.summary }}</text>
          <text v-for="sec in sectors" :key="sec" class="sec">{{ sec }}</text>
          <text
            v-for="st in anaStocks"
            :key="st.key"
            class="astk"
            :class="st.cls"
            :title="st.tip"
          >{{ st.label }}{{ st.arrow }}</text>
          <text v-if="analysis.inference" class="inf">↳ {{ analysis.inference }}</text>
        </template>
        <view
          v-else-if="canAnalyze"
          class="act"
          :class="{ busy: analyzing || item.analysisStatus === 'analyzing', err: item.analysisStatus === 'failed' }"
          @tap.stop="emit('analyze')"
        >
          <text v-if="analyzing || item.analysisStatus === 'analyzing'" class="spinner" />
          <text>{{ actionLabel }}</text>
        </view>
      </view>
    </view>
  </view>
</template>

<style scoped>
.item {
  display: flex;
  gap: 14px;
  padding: 14px 0;
  border-bottom: 0.5px solid var(--line);
}

.time {
  width: 52px;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding-top: 2px;
  font-size: 12px;
  font-variant-numeric: tabular-nums;
  color: var(--dim);
}
.hm {
  font-size: 12px;
}
.dd {
  font-size: 10px;
  opacity: 0.72;
}
.dd.today {
  opacity: 0.9;
  color: var(--amber);
}

.lv {
  font-size: 10px;
  line-height: 1.4;
  padding: 0 4px;
  border-radius: 3px;
  align-self: flex-start;
  border: 0.5px solid var(--line-2);
  color: var(--dim);
}
.lv.a {
  color: var(--on-amber);
  background: var(--amber);
  border-color: var(--amber);
}
.lv.b {
  color: var(--amber);
  border-color: var(--amber-line);
}

.body {
  flex: 1;
  min-width: 0;
}
.txt {
  font-size: 14px;
  line-height: 1.65;
  color: var(--text);
  white-space: pre-wrap;
  word-break: break-word;
}
/* 有原文链接时给一点可点的暗示。移动端没有 hover，靠点下去的回弹区分 */
.txt.src {
  cursor: pointer;
}
.txt.src:active {
  opacity: 0.62;
}
.hit {
  background: var(--mark-bg);
  border-radius: 2px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 8px;
}
.chip {
  font-size: 11px;
  color: var(--muted);
  border: 0.5px solid var(--line-2);
  border-radius: 4px;
  padding: 1px 6px;
}
.chip .code {
  color: var(--dim);
  font-size: 10px;
  margin-left: 4px;
}

.ana {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  min-height: 22px;
}
.pill {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 4px;
  border: 0.5px solid;
}
.pill.up {
  color: var(--up);
  border-color: var(--up-line);
}
.pill.down {
  color: var(--down);
  border-color: var(--down-line);
}
.pill.flat {
  color: var(--muted);
  border-color: var(--line-2);
}
.why {
  font-size: 12px;
  color: var(--muted);
}
.sec {
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 3px;
  color: var(--sec-fg);
  background: var(--sec-bg);
  border: 0.5px solid var(--sec-line);
}
.astk {
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 3px;
  border: 0.5px solid var(--line-2);
}
.astk.up {
  color: var(--up);
  border-color: var(--up-line-weak);
}
.astk.down {
  color: var(--down);
  border-color: var(--down-line-weak);
}
.astk.flat {
  color: var(--muted);
}
.inf {
  font-size: 11px;
  color: var(--infer);
  font-style: italic;
}

.act {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  line-height: 1.7;
  padding: 1px 8px;
  border-radius: 4px;
  border: 0.5px solid var(--line-2);
  color: var(--muted);
  cursor: pointer;
}
.act.busy {
  opacity: 0.55;
  cursor: default;
}
.act.err {
  color: var(--up);
  border-color: var(--up-line-weak);
}
.spinner {
  width: 10px;
  height: 10px;
  border: 1.5px solid var(--line-2);
  border-top-color: var(--muted);
  border-radius: 50%;
  animation: spin 0.7s linear infinite;
}
@keyframes spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
