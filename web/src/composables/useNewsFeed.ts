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
/** 增量一次取多少条。后端也按这个上限截断，所以「积压超过一页」是常态，不是异常 */
const REFRESH_LIMIT = 50
/**
 * 一轮增量最多追几次。
 *
 * 积压（停机、锁屏、批量补数据）超过一页时，靠 `hasMore` 继续追同一个游标，
 * 直到后端说没有了。设上限是为了不让一个坏掉的 hasMore 把网络打成死循环；
 * 追不完不要紧 —— 游标停在真正交付到的位置，下一轮 30 秒接着追。
 */
const REFRESH_ROUNDS = 5

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
  /**
   * 增量游标 = 后端交付过的最后一条 id（响应里的 `nextSince`）。
   *
   * 以前这里存的是全局最大 id，配合「一次只取 50 条」，停机或锁屏回来积压超过 50 条时
   * 游标会直接跳到最末尾，中间那批永远不会再被拉到 —— 表现为「少了几条，手动刷新才回来」。
   * 现在只推进到真正拿到的位置，没拿到就不动。
   */
  const nextSince = ref(0)
  /** 搜索命中总数；列表模式下恒为 0，不显示 */
  const matched = ref(0)
  let requestSeq = 0
  let timer: ReturnType<typeof setInterval> | null = null
  let visible = true
  /** 增量请求是否在飞（见 pullNewer 里的说明） */
  let pulling = false

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
      // 只有信息流才推进增量游标。搜索结果是另一条时间线，拿它推进会让
      // 「搜索期间到达的新条目」在退出搜索后被当成已看过 —— 那正是另一条丢条路径。
      if (!keyword.value) {
        nextSince.value = page.nextSince ?? 0
      }
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
   *
   * 三段容易写错的逻辑，都是真的踩过：
   *   1. **游标只能推到 `nextSince`**（后端交付到的最后一条），不能推到全局 maxId。
   *      maxId 意味着「没拿到的那部分也算看过了」，积压超过一页时会静默丢条目。
   *   2. **`hasMore` 要追**。后端一次最多 50 条，锁屏两分钟就有两轮采集（最多 100 条），
   *      只取一次等于丢掉后半段。追的时候用同一个游标往后走，追不完下一轮接着追。
   *   3. **顺序要反转**。后端按 id 升序返回（旧的在前），列表是新的在上，
   *      直接 concat 会让同一批里更早发布的那条显示在上面。批量小的时候看不出来，
   *      补一批 30 条就很明显。
   */
  async function pullNewer(silent = true): Promise<void> {
    // 搜索模式下不插队：插进来的新条目不属于这次搜索，会让人以为关键词匹配错了
    if (keyword.value || loading.value || loadingMore.value) return
    if (!nextSince.value) return
    const my = requestSeq
    const seen = new Set(items.value.map((x) => x.id))
    // 跨轮收集，最后一次性反转：分轮反转会把轮次顺序也翻掉
    const fresh: NewsItem[] = []
    // 游标先记在局部变量里，等条目真的并进列表了再提交。
    // 边追边提交会留下一个更隐蔽的丢条口子：第三轮网络断了，
    // 游标已经推进到第二轮的末尾，而那一批还在 fresh 里没进列表 ——
    // 下次轮询从新游标开始，第二轮那批永远不会回来。
  let cursor = nextSince.value
  // 上一轮还在飞就跳过这一轮（网络慢、会话失效后在等重登时都会出现）。
  // 没有这个锁的话，定时器每 30 秒再叠一个上去，登录成功后一起唤醒，
  // 同一段数据会被并发拉好几遍。
  if (pulling) return
  pulling = true
  try {
    for (let round = 0; round < REFRESH_ROUNDS; round++) {
      const page = await fetchNewer(cursor, REFRESH_LIMIT)
      if (my !== requestSeq) return

        if (page.stats) {
          stats.value = page.stats
          const auth = useAuth()
          if (auth.logged) auth.stats = page.stats
        }

        // 后端没给 nextSince（老版本）时退回到本页最大 id：至少不比「全局 maxId」更差
        const last = page.nextSince ?? page.items.reduce((m, x) => Math.max(m, x.id), cursor)
        // 游标没有前进就停：要么真没新条目，要么后端没按契约回，继续追只是空转
        if (last <= cursor) break
        cursor = last

        for (const it of page.items) {
          if (seen.has(it.id)) continue
          seen.add(it.id)
          fresh.push(it)
        }
        if (!page.hasMore) break
      }
      if (cursor === nextSince.value) return
      nextSince.value = cursor
      if (!fresh.length) return
      // id 升序 → 反转成新的在上
      items.value = fresh.reverse().concat(items.value)
    } catch (e) {
      // 静默轮询失败不弹提示：网络抖一下就红字，比不刷新更烦人
      if (!silent) notice.value = e instanceof ApiError ? e.message : '刷新失败'
    } finally {
      pulling = false
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
    nextSince,
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
