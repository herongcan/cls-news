import type { Sentiment } from '@/api/types'

const pad = (n: number) => (n < 10 ? '0' + n : String(n))

export interface Stamp {
  /** HH:mm */
  hm: string
  /** MM-DD，跨天时读起来不瞎 */
  day: string
  today: boolean
}

export function stamp(sec: number): Stamp {
  const d = new Date(sec * 1000)
  const now = new Date()
  const today =
    d.getFullYear() === now.getFullYear() &&
    d.getMonth() === now.getMonth() &&
    d.getDate() === now.getDate()
  return {
    hm: pad(d.getHours()) + ':' + pad(d.getMinutes()),
    day: pad(d.getMonth() + 1) + '-' + pad(d.getDate()),
    today
  }
}

/** 顶栏的短时间戳：今天只给时分，非今天补日期 */
export function stampShort(sec: number): string {
  const s = stamp(sec)
  return s.today ? s.hm : s.day + ' ' + s.hm
}

export interface Segment {
  t: string
  hit: boolean
}

/**
 * 把正文按关键词切成「命中 / 未命中」片段。
 *
 * 不用 v-html 拼 <mark>：小程序没有 DOM，v-html 只能落到 rich-text，
 * 而 rich-text 支持的内联标签有限，mark 不在其中。切成片段交给模板渲染 <text>，
 * 两端表现一致，也不会因为关键词里带尖括号而多出一个注入面。
 */
export function splitByKeyword(text: string, keyword: string): Segment[] {
  const kw = (keyword || '').trim()
  if (!kw) return [{ t: text, hit: false }]

  const lowerText = text.toLowerCase()
  const lowerKw = kw.toLowerCase()
  const out: Segment[] = []
  let from = 0

  for (;;) {
    const at = lowerText.indexOf(lowerKw, from)
    if (at < 0) break
    if (at > from) out.push({ t: text.slice(from, at), hit: false })
    out.push({ t: text.slice(at, at + kw.length), hit: true })
    from = at + kw.length
    if (from >= text.length) break
  }
  if (from < text.length) out.push({ t: text.slice(from), hit: false })
  return out.length ? out : [{ t: text, hit: false }]
}

export function sentimentLabel(s?: Sentiment): string {
  if (s === 'bullish') return '利好'
  if (s === 'bearish') return '利空'
  return '中性'
}

/** 红涨绿跌，按 A 股习惯 */
export function sentimentClass(s?: Sentiment): string {
  if (s === 'bullish') return 'up'
  if (s === 'bearish') return 'down'
  return 'flat'
}

export function percent(v?: number): string {
  return typeof v === 'number' ? ' ' + Math.round(v * 100) + '%' : ''
}

/** 东方财富行情页只认 sh/sz/bj + 6 位数字，格式不对就不给跳转 */
export function quoteUrl(code?: string): string {
  return code && /^(sh|sz|bj)\d{6}$/.test(code)
    ? 'https://quote.eastmoney.com/' + code + '.html'
    : ''
}
