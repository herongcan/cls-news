import { computed, ref } from 'vue'
import { ApiError } from '@/api/http'
import { analyzeNews, fetchBefore, fetchLatest, fetchNewer, fetchOne, searchNews } from '@/api/news'
import type { NewsItem, Stats } from '@/api/types'
import { useAuth } from '@/stores/auth'

const PAGE = 10
const POLL_INTERVAL = 3000
const POLL_MAX = 20
/** 列表静默增量：30 秒拉一次新条目。采集端 90 秒一轮，比它快一档才能做到「几乎实时」 */
const REFRESH_MS = 30000
/** 增量不分页，一次取走上限。积压通常个位数，给 50 是防长时间挂后台后回来 */
const REFRESH_LIMIT = 50

const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms))

export type FeedState = 'loading' | 'idle' | 'more' | 'end' | 'err' | 'empty'

/**
 * 列表的全部状态机。
 *
 * 两处容易写错的地方，跟原来手写页面时踩的坑一样：
 *   1. 搜索词连打时响应会乱序回来。用一个自增的 requestSeq 当哨兵，
 *      只认最后一次请求的结果，否则「搜 A 又搜 B」最后可能停在 A 的结果上。
 *   2. 翻页是游标式（before=当前最后一条 id），不是 pageNo。
 *      所以搜索和列表可以共用同一套翻页逻辑，只要把请求换成 search 即可。
 */
export function useNewsFeed() {
  const items = ref<NewsItem[]>([])
  const stats = ref<Stats | null>(null)
  const keyword = ref('')
  const loading = ref(false)
  const loadingMore = ref(false)
  const hasMore = ref(true)
  const error = ref('')
  /** 分析触发失败之类的临时提示，与列表加载失败区分开 */
  const notice = ref('')
  const analyzing = ref<number[]>([])
  /** 服务端返回的全局最大 id，增量拉取的游标 */
  const maxId = ref(0)
  /** 搜索命中总数；列表模式下恒为 0，不显示 */
  const matched = ref(0)
  let requestSeq = 0
  let timer: ReturnType<typeof setInterval> | null = null
  let visible = true

  const cursor = computed(() => (items.value.length ? items.value[items.value.length - 1].id : 0))

  const state = computed<FeedState>(() => {
    if (loading.value) return 'loading'
    if (error.value) return 'err'
    if (loadingMore.value) return 'more'
    if (!items.value.length) return keyword.value ? 'empty' : hasMore.value ? 'loading' : 'empty'
    return hasMore.value ? 'idle' : 'end'
  })

  function isAnalyzing(id: number): boolean {
    return analyzing.value.indexOf(id) >= 0
  }

  function patch(id: number, next: Partial<NewsItem>): void {
    const i = items.value.findIndex((x) => x.id === id)
    if (i >= 0) items.value[i] = { ...items.value[i], ...next }
  }

  function replace(item: NewsItem): void {
    const i = items.value.findIndex((x) => x.id === item.id)
    if (i >= 0) items.value[i] = item
  }

  async function reload(): Promise<void> {
    const my = ++requestSeq
    loading.value = true
    error.value = ''
    try {
      const page = keyword.value
        ? await searchNews(keyword.value, 0, PAGE)
        : await fetchLatest(PAGE)
      if (my !== requestSeq) return
      items.value = page.items
      hasMore.value = page.hasMore
      maxId.value = page.maxId
      matched.value = keyword.value ? page.total ?? page.items.length : 0
      // 搜索结果不带 stats：这时不能把顶栏清成 0，否则「已收录 0 条」会一直挂着
      if (page.stats) {
        stats.value = page.stats
        const auth = useAuth()
        if (auth.logged) auth.stats = page.stats
      }
    } catch (e) {
      if (my !== requestSeq) return
      error.value = e instanceof ApiError ? e.message : '加载失败'
    } finally {
      if (my === requestSeq) loading.value = false
    }
  }

  async function loadMore(): Promise<void> {
    if (loading.value || loadingMore.value || !hasMore.value) return
    const my = requestSeq
    loadingMore.value = true
    error.value = ''
    try {
      const page = keyword.value
        ? await searchNews(keyword.value, cursor.value, PAGE)
        : await fetchBefore(cursor.value, PAGE)
      if (my !== requestSeq) return
      const seen = new Set(items.value.map((x) => x.id))
      items.value = items.value.concat(page.items.filter((x) => !seen.has(x.id)))
      hasMore.value = page.hasMore
    } catch (e) {
      if (my !== requestSeq) return
      error.value = e instanceof ApiError ? e.message : '加载失败'
    } finally {
      if (my === requestSeq) loadingMore.value = false
    }
  }

  async function search(kw: string): Promise<void> {
    keyword.value = kw.trim()
    await reload()
  }

  /**
   * 静默增量：把 since 之后的新条目插到列表头部。
   *
   * 为什么不是简单重跑 reload：重跑会把用户已经滚动翻到第 5 页的列表整体替换掉，
   * 屏幕会跳回顶部。只往前插，翻页游标和滚动位置都不受影响。
   */
  async function pullNewer(silent = true): Promise<void> {
    // 搜索模式下不插队：插进来的新条目不属于这次搜索，会让人以为关键词匹配错了
    if (keyword.value || loading.value || loadingMore.value) return
    const since = maxId.value
    if (!since) return
    const my = requestSeq
    try {
      const page = await fetchNewer(since, REFRESH_LIMIT)
      if (my !== requestSeq) return
      maxId.value = page.maxId
      if (page.stats) {
        stats.value = page.stats
        const auth = useAuth()
        if (auth.logged) auth.stats = page.stats
      }
      if (!page.items.length) return
      const seen = new Set(items.value.map((x) => x.id))
      const fresh = page.items.filter((x) => !seen.has(x.id))
      if (!fresh.length) return
      items.value = fresh.concat(items.value)
    } catch (e) {
      // 静默轮询失败不弹提示：网络抖一下就红字，比不刷新更烦人
      if (!silent) notice.value = e instanceof ApiError ? e.message : '刷新失败'
    }
  }

  /** 页面挂载时启动定时器；切到后台不轮询，回来立刻补一次 */
  function start(): void {
    stop()
    timer = setInterval(() => {
      if (visible) void pullNewer()
    }, REFRESH_MS)
  }

  function stop(): void {
    if (timer) {
      clearInterval(timer)
      timer = null
    }
  }

  function setVisible(next: boolean): void {
    visible = next
    if (next) void pullNewer()
  }

  async function analyze(id: number): Promise<void> {
    if (isAnalyzing(id)) return
    notice.value = ''
    analyzing.value = [...analyzing.value, id]
    // 乐观置为 analyzing：按钮立刻变「分析中」，否则点了没反应人会怀疑没点上
    patch(id, { analysisStatus: 'analyzing' })
    try {
      const res = await analyzeNews(id)
      replace(res.item)
      if (res.item.analysisStatus === 'analyzing') await poll(id)
      // 顺手刷一次顶栏的「今日调用」：等 30 秒的静默增量再更新，看起来像这次没记账
      const auth = useAuth()
      if (auth.logged) void auth.sync().catch(() => undefined)
    } catch (e) {
      patch(id, { analysisStatus: 'failed' })
      notice.value = e instanceof ApiError ? e.message : '分析失败'
    } finally {
      analyzing.value = analyzing.value.filter((x) => x !== id)
    }
  }

  /** 后端可能异步分析（queued），轮询到出结论或超时为止，别让人对着「分析中」干等 */
  async function poll(id: number): Promise<void> {
    for (let i = 0; i < POLL_MAX; i++) {
      await sleep(POLL_INTERVAL)
      try {
        const item = await fetchOne(id)
        replace(item)
        if (item.analysisStatus !== 'analyzing') return
      } catch (e) {
        return
      }
    }
    patch(id, { analysisStatus: 'pending' })
    notice.value = '分析超时，稍后再试'
  }

  return {
    items,
    stats,
    keyword,
    loading,
    loadingMore,
    hasMore,
    error,
    notice,
    analyzing,
    matched,
    maxId,
    state,
    cursor,
    reload,
    loadMore,
    search,
    analyze,
    isAnalyzing,
    pullNewer,
    start,
    stop,
    setVisible
  }
}
